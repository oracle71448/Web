package edu.campus.venue;

import java.security.Principal;
import java.time.*;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import static edu.campus.venue.Booking.Status.*;

@RestController
@RequestMapping("/api")
public class DashboardController {
    private final BookingService bookingService;
    private final BookingRepository bookings;
    private final VenueRepository venues;
    private final Clock clock;
    private final Environment env;

    public DashboardController(BookingService bookingService, BookingRepository bookings, VenueRepository venues, Clock clock, Environment env) {
        this.bookingService = bookingService;
        this.bookings = bookings;
        this.venues = venues;
        this.clock = clock;
        this.env = env;
    }

    @GetMapping("/dashboard")
    @Transactional(readOnly = true)
    Map<String, Object> dashboard(Principal p) {
        AppUser user = bookingService.currentUser(p.getName());
        boolean admin = user.role.equals("ADMIN");
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        LocalDate firstDay = today.minusDays(6);

        var counts = bookings.dashboardCounts(admin, user.id, today, now.getHour(), PENDING, APPROVED, CHECKED_IN);
        Map<LocalDate, Long> bookedByDate = new HashMap<>();
        for (var day : bookings.dailyBookingCounts(admin, user.id, firstDay, today, now.getHour(), List.of(PENDING, APPROVED, CHECKED_IN), PENDING)) {
            bookedByDate.put(day.getBookingDate(), day.getBookingCount());
        }
        List<Map<String, Object>> trend = new ArrayList<>();
        for (LocalDate day = firstDay; !day.isAfter(today); day = day.plusDays(1)) {
            trend.add(Map.of("date", day, "count", bookedByDate.getOrDefault(day, 0L)));
        }

        var recent = admin ? bookings.findFirst5ByOrderByCreatedAtDescIdDesc()
                           : bookings.findFirst5ByUserIdOrderByCreatedAtDescIdDesc(user.id);
        return Map.of("venueCount", venues.count(), "openVenues", venues.countByEnabledTrue(),
                "todayBookings", counts.getTodayBookings(), "pendingBookings", counts.getPendingBookings(),
                "totalBookings", counts.getTotalBookings(), "trend", trend,
                "recentBookings", recent.stream().map(booking -> bookingService.view(booking, user, now)).toList());
    }

    @GetMapping("/system") Map<String, Object> system() { return Map.of("demo", Arrays.asList(env.getActiveProfiles()).contains("demo") || (env.getActiveProfiles().length == 0 && Arrays.asList(env.getDefaultProfiles()).contains("demo")), "date", LocalDate.now(clock)); }
}
