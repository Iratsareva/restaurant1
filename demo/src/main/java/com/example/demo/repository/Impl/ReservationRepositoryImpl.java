package com.example.demo.repository.Impl;

import com.example.demo.models.Reservation;
import com.example.demo.repository.AbstractRepository;
import com.example.demo.repository.ReservationRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Repository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Repository
public class ReservationRepositoryImpl extends AbstractRepository<Reservation> implements ReservationRepository {

    @PersistenceContext
    private EntityManager entityManager;

    public ReservationRepositoryImpl() {
        super(Reservation.class);
    }

    @Override
    public List<Reservation> findByClientId(Long clientId) {
        TypedQuery<Reservation> query = entityManager.createQuery(
                "SELECT r FROM Reservation r WHERE r.client.id = :clientId", Reservation.class);
        return query.setParameter("clientId", clientId).getResultList();
    }

    @Override
    public List<Reservation> findByTableId(Long tableId) {
        TypedQuery<Reservation> query = entityManager.createQuery(
                "SELECT r FROM Reservation r WHERE r.table.id = :tableId", Reservation.class);
        return query.setParameter("tableId", tableId).getResultList();
    }

    @Override
    public List<Reservation> findByStatus(String status) {
        TypedQuery<Reservation> query = entityManager.createQuery(
                "SELECT r FROM Reservation r WHERE r.status = :status", Reservation.class);
        return query.setParameter("status", status).getResultList();
    }

    @Override
    public List<Reservation> findOverlapping(Long tableId, LocalDateTime start, LocalDateTime end) {
        // Проверяем пересечения: новое бронирование пересекается с существующим, если:
        // (start < existing_end) AND (end > existing_start)
        // Где existing_start = r.reservationTime, existing_end = r.reservationTime + 2 часа
        // Упрощенная проверка: r.reservationTime < end AND (r.reservationTime + 2 часа) > start
        // Используем простую проверку в Java, так как TIMESTAMPADD может не работать во всех БД
        TypedQuery<Reservation> query = entityManager.createQuery(
                "SELECT r FROM Reservation r " +
                        "WHERE r.table.id = :tableId " +
                        "AND r.status != 'CANCELLED' " +  // Исключаем отмененные бронирования
                        "AND r.reservationTime < :end", Reservation.class);
        List<Reservation> candidates = query.setParameter("tableId", tableId)
                .setParameter("end", end)
                .getResultList();
        
        // Фильтруем в Java: проверяем, что (r.reservationTime + 2 часа) > start
        Duration twoHours = Duration.ofHours(2);
        return candidates.stream()
                .filter(r -> r.getReservationTime().plus(twoHours).isAfter(start))
                .collect(Collectors.toList());
    }


    @Override
    public List<Reservation> findByTableType(String type) {
        TypedQuery<Reservation> query = entityManager.createQuery(
                "SELECT r FROM Reservation r WHERE r.table.type = :type", Reservation.class);
        return query.setParameter("type", type).getResultList();
    }

}

