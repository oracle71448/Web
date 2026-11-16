package edu.campus.venue;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;
import static edu.campus.venue.Booking.Status.*;

@Service
@Transactional(readOnly = true)
public class BookingService {
    private static final Set<Booking.Status> OCCUPYING_STATUSES = Set.of(PENDING, APPROVED, CHECKED_IN);
    private final BookingRepository bookings;
    private final VenueRepository venues;
    private final UserRepository users;
    private final Clock clock;
    public BookingService(BookingRepository bookings, VenueRepository venues, UserRepository users, Clock clock) {
        this.bookings = bookings; this.venues = venues; this.users = users; this.clock = clock;
    }
    AppUser currentUser(String username) { return users.findByUsername(username).orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "请先登录")); }
    public List<ApiModels.BookingView> list(String username) {
        AppUser user = currentUser(username);
        LocalDateTime now = LocalDateTime.now(clock);
        return (user.role.equals("ADMIN") ? bookings.findAllByOrderByCreatedAtDesc() : bookings.findByUserIdOrderByCreatedAtDesc(user.id))
                .stream().map(b -> view(b, user, now)).toList();
    }
    public List<ApiModels.Slot> slots(Long venueId, LocalDate date) {
        Venue venue = venues.findById(venueId).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "场馆不存在"));
        LocalDateTime now = LocalDateTime.now(clock);
        validateDate(date, now.toLocalDate());
        Set<Integer> occupied = new HashSet<>();
        bookings.findByVenueIdAndDate(venueId, date).stream().filter(b -> occupiesSlots(b, now)).forEach(b -> {
            for (int hour = b.startHour; hour < b.startHour + b.durationHours; hour++) occupied.add(hour);
        });
        List<ApiModels.Slot> result = new ArrayList<>();
        for (int hour = venue.openHour; hour < venue.closeHour; hour++) {
            String status = !venue.enabled ? "CLOSED" : !date.atTime(hour, 0).isAfter(now) ? "EXPIRED" : occupied.contains(hour) ? "BUSY" : "AVAILABLE";
            result.add(new ApiModels.Slot(hour, status));
        }
        return result;
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ApiModels.BookingView create(String username, ApiModels.BookingRequest request) {
        AppUser user = currentUser(username);
        if (!user.role.equals("STUDENT")) throw new ResponseStatusException(FORBIDDEN, "管理员账号仅用于管理，请使用学生账号预约");
        // 所有对同一场馆预约状态的写操作先锁场馆，防止并发重复占用。
        Venue venue = venues.lockById(request.venueId()).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "场馆不存在"));
        LocalDateTime now = LocalDateTime.now(clock);
        validateDate(request.date(), now.toLocalDate());
        if (request.durationHours() < 1 || request.durationHours() > 2) throw new ResponseStatusException(BAD_REQUEST, "每次可预约 1 或 2 小时");
        if (!venue.enabled) throw new ResponseStatusException(CONFLICT, "场馆暂未开放预约");
        if (request.startHour() < venue.openHour || request.startHour() + request.durationHours() > venue.closeHour) throw new ResponseStatusException(BAD_REQUEST, "预约须在开放时间内结束");
        if (!request.date().atTime(request.startHour(), 0).isAfter(now)) throw new ResponseStatusException(BAD_REQUEST, "不能预约已经开始的时段");
        if (request.participants() > venue.capacity) throw new ResponseStatusException(BAD_REQUEST, "预约人数超过场馆容量");
        boolean overlaps = bookings.findByVenueIdAndDate(venue.id, request.date()).stream()
                .anyMatch(b -> occupiesSlots(b, now) && b.startHour < request.startHour() + request.durationHours()
                        && request.startHour() < b.startHour + b.durationHours);
        if (overlaps) throw new ResponseStatusException(CONFLICT, "所选时段已被预约，请选择其他时段");
        Booking b = new Booking();
        b.user = user; b.venue = venue; b.date = request.date(); b.startHour = request.startHour(); b.durationHours = request.durationHours(); b.participants = request.participants();
        b.purpose = request.purpose().trim(); b.status = PENDING; b.createdAt = now;
        return view(bookings.save(b), user, now);
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ApiModels.BookingView changeStatus(String username, Long id, String action) {
        AppUser user = currentUser(username);
        Long venueId = bookings.findVenueId(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "预约不存在"));
        venues.lockById(venueId).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "场馆不存在"));
        Booking b = bookings.findById(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "预约不存在"));
        boolean admin = user.role.equals("ADMIN");
        if (!admin && !b.user.id.equals(user.id)) throw new ResponseStatusException(FORBIDDEN, "只能操作自己的预约");
        LocalDateTime now = LocalDateTime.now(clock), start = b.date.atTime(b.startHour, 0);
        switch (action) {
            case "approve", "reject" -> {
                if (!admin) throw new ResponseStatusException(FORBIDDEN, "需要管理员权限");
                if (b.status != PENDING) throw new ResponseStatusException(CONFLICT, "只有待审核预约可以审核");
                if (!start.isAfter(now)) throw new ResponseStatusException(CONFLICT, "预约已过期，无法审核");
                b.status = action.equals("approve") ? APPROVED : REJECTED;
            }
            case "cancel" -> {
                if (b.status != PENDING && b.status != APPROVED) throw new ResponseStatusException(CONFLICT, "当前状态无法取消");
                if (!start.isAfter(now)) throw new ResponseStatusException(CONFLICT, "预约开始后无法取消");
                b.status = CANCELLED;
            }
            case "check-in" -> {
                if (!user.role.equals("STUDENT") || !b.user.id.equals(user.id)) throw new ResponseStatusException(FORBIDDEN, "仅预约学生本人可以签到");
                if (b.status != APPROVED) throw new ResponseStatusException(CONFLICT, "只有已通过预约可以签到");
                if (!withinCheckInWindow(now, start, b.durationHours)) throw new ResponseStatusException(CONFLICT, "请在开始前 15 分钟至时段结束前签到");
                b.status = CHECKED_IN; b.checkedInAt = now;
            }
            default -> throw new ResponseStatusException(BAD_REQUEST, "不支持的操作");
        }
        return view(b, user, now);
    }
    ApiModels.BookingView view(Booking b, AppUser actor, LocalDateTime now) {
        LocalDateTime start = b.date.atTime(b.startHour, 0);
        if (isExpiredPending(b, now)) return ApiModels.BookingView.from(b, "EXPIRED", List.of());
        if (b.status == APPROVED && !now.isBefore(start.plusHours(b.durationHours))) {
            return ApiModels.BookingView.from(b, "NO_SHOW", List.of());
        }

        List<String> actions = new ArrayList<>();
        boolean admin = actor.role.equals("ADMIN"), owner = b.user.id.equals(actor.id);
        if (admin && b.status == PENDING) {
            actions.add("approve");
            actions.add("reject");
        }
        if ((admin || owner) && (b.status == PENDING || b.status == APPROVED) && start.isAfter(now)) {
            actions.add("cancel");
        }
        if (owner && actor.role.equals("STUDENT") && b.status == APPROVED && withinCheckInWindow(now, start, b.durationHours)) {
            actions.add("check-in");
        }
        return ApiModels.BookingView.from(b, b.status.name(), List.copyOf(actions));
    }
    private static boolean isExpiredPending(Booking booking, LocalDateTime now) {
        return booking.status == PENDING && !booking.date.atTime(booking.startHour, 0).isAfter(now);
    }
    private static boolean occupiesSlots(Booking booking, LocalDateTime now) {
        return OCCUPYING_STATUSES.contains(booking.status) && !isExpiredPending(booking, now);
    }
    private static boolean withinCheckInWindow(LocalDateTime now, LocalDateTime start, int durationHours) {
        return !now.isBefore(start.minusMinutes(15)) && now.isBefore(start.plusHours(durationHours));
    }
    private static void validateDate(LocalDate date, LocalDate today) {
        if (date.isBefore(today) || date.isAfter(today.plusDays(14))) throw new ResponseStatusException(BAD_REQUEST, "仅可预约今天起 14 天内的时段");
    }
}
