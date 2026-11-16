package edu.campus.venue;
import java.time.LocalDate;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface BookingRepository extends JpaRepository<Booking, Long> {
    @Override @EntityGraph(attributePaths = {"user", "venue"})
    Optional<Booking> findById(Long id);
    @Query("select b.venue.id from Booking b where b.id = :id")
    Optional<Long> findVenueId(@Param("id") Long id);
    @EntityGraph(attributePaths = {"user", "venue"})
    List<Booking> findAllByOrderByCreatedAtDesc();
    @EntityGraph(attributePaths = {"user", "venue"})
    List<Booking> findByUserIdOrderByCreatedAtDesc(Long userId);
    List<Booking> findByVenueIdAndDate(Long venueId, LocalDate date);

    @EntityGraph(attributePaths = {"user", "venue"})
    List<Booking> findFirst5ByOrderByCreatedAtDescIdDesc();

    @EntityGraph(attributePaths = {"user", "venue"})
    List<Booking> findFirst5ByUserIdOrderByCreatedAtDescIdDesc(Long userId);

    @Query("""
            select count(b) as totalBookings,
                   coalesce(sum(case when b.status = :pending and
                       (b.date > :today or (b.date = :today and b.startHour > :nowHour))
                       then 1 else 0 end), 0) as pendingBookings,
                   coalesce(sum(case when b.date = :today and (
                       b.status = :checkedIn
                       or (b.status = :pending and b.startHour > :nowHour)
                       or (b.status = :approved and b.startHour + b.durationHours > :nowHour)
                   ) then 1 else 0 end), 0) as todayBookings
            from Booking b
            where (:includeAllUsers = true or b.user.id = :userId)
            """)
    DashboardCounts dashboardCounts(@Param("includeAllUsers") boolean includeAllUsers, @Param("userId") Long userId,
                                    @Param("today") LocalDate today, @Param("nowHour") int nowHour,
                                    @Param("pending") Booking.Status pending, @Param("approved") Booking.Status approved,
                                    @Param("checkedIn") Booking.Status checkedIn);

    @Query("""
            select b.date as bookingDate, count(b) as bookingCount
            from Booking b
            where (:includeAllUsers = true or b.user.id = :userId)
              and b.date between :fromDate and :today
              and b.status in :includedStatuses
              and (b.status <> :pending or (b.date = :today and b.startHour > :nowHour))
            group by b.date
            """)
    List<DailyBookingCount> dailyBookingCounts(@Param("includeAllUsers") boolean includeAllUsers, @Param("userId") Long userId,
                                               @Param("fromDate") LocalDate fromDate, @Param("today") LocalDate today,
                                               @Param("nowHour") int nowHour, @Param("includedStatuses") Collection<Booking.Status> includedStatuses,
                                               @Param("pending") Booking.Status pending);

    interface DashboardCounts {
        long getTotalBookings();
        long getPendingBookings();
        long getTodayBookings();
    }

    interface DailyBookingCount {
        LocalDate getBookingDate();
        long getBookingCount();
    }
}
