package com.example.demo.service;



import com.example.Restaurant.dto.*;
import com.example.Restaurant.exception.ConflictException;
import com.example.Restaurant.exception.ResourceNotFoundException;

import com.example.demo.config.RabbitMQConfig;
import com.example.demo.models.Client;
import com.example.demo.models.Reservation;
import com.example.demo.models.TableEntity;
import com.example.demo.repository.ClientRepository;
import com.example.demo.repository.ReservationRepository;
import com.example.demo.repository.TableRepository;
import org.example.restaurant.events.ReservationCreatedEvent;
import org.example.restaurant.events.ReservationDeletedEvent;
import org.example.restaurant.events.ReservationStatusChangedEvent;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.example.reservationprice.PriceRequest;
import org.example.reservationprice.PriceResponse;
import org.example.reservationprice.ReservationPriceServiceGrpc;
import io.grpc.StatusRuntimeException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.example.restaurant.events.ReservationPricedEvent;

@Service
public class ReservationService {
    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);
    private final ReservationRepository reservationRepository;
    private final TableRepository tableRepository;
    private final ClientRepository clientRepository;
    private final ClientService clientService;
    private final TableService tableService;
    private final RabbitTemplate rabbitTemplate; // Добавляем RabbitTemplate
    
    @GrpcClient("reservation-price-service")
    private ReservationPriceServiceGrpc.ReservationPriceServiceBlockingStub priceServiceStub;



    private static final Duration RESERVATION_DURATION = Duration.ofHours(2); // Продолжительность брони

    public ReservationService(ReservationRepository reservationRepository, TableRepository tableRepository, ClientRepository clientRepository, ClientService clientService, TableService tableService, RabbitTemplate rabbitTemplate) {
        this.reservationRepository = reservationRepository;
        this.tableRepository = tableRepository;
        this.clientRepository = clientRepository;
        this.clientService = clientService;
        this.tableService = tableService;
        this.rabbitTemplate = rabbitTemplate;
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public PagedResponse<ReservationResponse> findAll(Long clientId, Long tableId, String status,String tableType, int page, int size) {
        List<Reservation> reservations;
        if (clientId != null) {
            reservations = reservationRepository.findByClientId(clientId);
        } else if (tableId != null) {
            reservations = reservationRepository.findByTableId(tableId);
        } else if (status != null) {
            reservations = reservationRepository.findByStatus(status);
        }else if (tableType != null) {
            reservations = reservationRepository.findByTableType(tableType);
        } else {
            reservations = reservationRepository.findAll();
        }

        int totalElements = reservations.size();
        int totalPages = (int) Math.ceil((double) totalElements / size);
        int from = page * size;
        int to = Math.min(from + size, totalElements);
        
        // Защита от IndexOutOfBoundsException при пустом списке
        List<Reservation> pageContent = (from >= totalElements || from < 0) 
                ? List.of() 
                : reservations.subList(from, to);

        List<ReservationResponse> content = pageContent.stream().map(this::toResponse).collect(Collectors.toList());
        boolean last = page >= totalPages - 1;

        return new PagedResponse<>(content, page, size, totalElements, totalPages, last);
    }

    public ReservationResponse findById(Long id) {
        Reservation reservation = reservationRepository.findById(id);
        if (reservation == null) {
            throw new ResourceNotFoundException("Reservation", id);
        }
        return toResponse(reservation);
    }






    public ReservationResponse create(ReservationRequest request) {
        Client clientEntity = clientRepository.findById(request.clientId());
        if (clientEntity == null) {
            throw new ResourceNotFoundException("Client", request.clientId());
        }

        TableEntity tableEntity = tableRepository.findById(request.tableId());
        if (tableEntity == null) {
            throw new ResourceNotFoundException("Table", request.tableId());
        }

        if (!tableEntity.isAvailable()) {
            throw new ConflictException("Стол недоступен для бронирования");
        }
        if (request.numberOfGuests() > tableEntity.getNumberOfSeats()) {
            throw new IllegalArgumentException("Количество гостей превышает количество мест за столом");
        }

        LocalDateTime endTime = request.reservationTime().plus(RESERVATION_DURATION);
        List<Reservation> overlapping = reservationRepository.findOverlapping(request.tableId(), request.reservationTime(), endTime);
        if (!overlapping.isEmpty()) {
            throw new ConflictException("Стол уже забронирован на указанное время");
        }

        Reservation reservation = new Reservation();
        reservation.setClient(clientEntity);
        reservation.setTable(tableEntity);
        reservation.setReservationTime(request.reservationTime());
        reservation.setNumberOfGuests(request.numberOfGuests());
        reservation.setStatus("PENDING");
        reservation.setCreatedAt(LocalDateTime.now()); // Устанавливаем время создания

        // Сначала сохраняем, чтобы получить ID
        reservation = reservationRepository.create(reservation);

        // СИНХРОННЫЙ расчет цены при создании, чтобы сразу вернуть цену в ответе
        try {
            if (priceServiceStub != null) {
                String tableType = tableEntity.getType() != null && !tableEntity.getType().isBlank() 
                        ? tableEntity.getType() 
                        : "STANDARD";
                
                PriceRequest priceRequest = PriceRequest.newBuilder()
                        .setReservationId(reservation.getId())
                        .setNumberOfGuests(reservation.getNumberOfGuests())
                        .setTableType(tableType)
                        .setDurationHours(2) // Фиксированная продолжительность
                        .build();

                PriceResponse priceResponse = priceServiceStub.calculatePrice(priceRequest);
                reservation.setPrice(priceResponse.getPrice());
                reservation = reservationRepository.update(reservation);
                
                log.info("Price calculated synchronously for reservationId={}, price={}", 
                        reservation.getId(), priceResponse.getPrice());
            } else {
                log.warn("gRPC price service stub is not available, price will be calculated asynchronously");
            }
        } catch (StatusRuntimeException e) {
            log.error("Error calculating price synchronously for reservationId={}: {}", 
                    reservation.getId(), e.getStatus(), e);
            // Продолжаем без цены, цена будет рассчитана асинхронно
        } catch (Exception e) {
            log.error("Unexpected error calculating price synchronously for reservationId={}: {}", 
                    reservation.getId(), e.getMessage(), e);
            // Продолжаем без цены, цена будет рассчитана асинхронно
        }

        // Публикуем событие создания (для асинхронных подписчиков: notification, audit)
        ReservationCreatedEvent event = new ReservationCreatedEvent(
                reservation.getId(),
                clientEntity.getId(),
                clientEntity.getName(),
                tableEntity.getId(),
                tableEntity.getNumber(),
                tableEntity.getType() != null ? tableEntity.getType() : "STANDARD",
                reservation.getReservationTime(),
                reservation.getNumberOfGuests()
        );

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EXCHANGE_NAME,
                RabbitMQConfig.ROUTING_KEY_RESERVATION_CREATED,
                event
        );

        log.info("Reservation created event published: reservationId={}", reservation.getId());

        return toResponse(reservation);
    }

    public ReservationResponse update(Long id, UpdateReservationRequest request) {
        Reservation reservation = reservationRepository.findById(id);
        if (reservation == null) {
            throw new ResourceNotFoundException("Reservation", id);
        }

        TableEntity tableEntity = reservation.getTable();

        if (request.numberOfGuests() > tableEntity.getNumberOfSeats()) {
            throw new IllegalArgumentException("Количество гостей превышает количество мест за столом");
        }

        LocalDateTime endTime = request.reservationTime().plus(RESERVATION_DURATION);
        List<Reservation> overlapping = reservationRepository.findOverlapping(tableEntity.getId(), request.reservationTime(), endTime);
        overlapping.removeIf(r -> r.getId().equals(id)); // Исключаем само бронирование
        if (!overlapping.isEmpty()) {
            throw new ConflictException("Стол уже забронирован на указанное время");
        }

        reservation.setReservationTime(request.reservationTime());
        reservation.setNumberOfGuests(request.numberOfGuests());

        // Для update также используем асинхронный расчет через Price Client
        // Публикуем событие изменения, Price Client пересчитает цену
        // (В реальном проекте может понадобиться отдельное событие UpdateReservationEvent)
        // Пока оставляем синхронный вызов для update, или можно тоже сделать асинхронным







        reservation = reservationRepository.update(reservation);
        return toResponse(reservation);
    }

    /**
     * Изменяет статус бронирования с валидацией допустимых переходов
     * 
     * Допустимые переходы:
     * - PENDING -> CONFIRMED (клиент подтвердил)
     * - PENDING -> PAID (клиент сразу оплатил)
     * - PENDING -> CANCELLED (клиент отменил или автоматически)
     * - CONFIRMED -> PAID (клиент оплатил после подтверждения)
     * - CONFIRMED -> CANCELLED (клиент отменил после подтверждения)
     * - PAID -> CANCELLED (клиент отменил после оплаты)
     * 
     * @param id ID бронирования
     * @param newStatus Новый статус
     * @return Обновленное бронирование
     */
    public ReservationResponse changeStatus(Long id, String newStatus) {
        Reservation reservation = reservationRepository.findById(id);
        if (reservation == null) {
            throw new ResourceNotFoundException("Reservation", id);
        }

        String currentStatus = reservation.getStatus();
        
        // Валидация переходов статусов
        if (!isValidStatusTransition(currentStatus, newStatus)) {
            throw new IllegalArgumentException(
                String.format("Недопустимый переход статуса: %s -> %s. " +
                    "Допустимые переходы: PENDING -> CONFIRMED/PAID/CANCELLED, CONFIRMED -> PAID/CANCELLED, PAID -> CANCELLED",
                    currentStatus, newStatus)
            );
        }

        // Проверка, что статус не тот же самый
        if (currentStatus.equals(newStatus)) {
            log.warn("Попытка изменить статус на тот же самый: reservationId={}, status={}", id, newStatus);
            return toResponse(reservation);
        }

        reservation.setStatus(newStatus);
        reservation = reservationRepository.update(reservation);
        
        log.info("Статус бронирования изменен: reservationId={}, {} -> {}", id, currentStatus, newStatus);
        
        // Публикуем событие изменения статуса
        ReservationStatusChangedEvent statusEvent = new ReservationStatusChangedEvent(
                reservation.getId(),
                reservation.getClient().getId(),
                currentStatus,
                newStatus
        );
        
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EXCHANGE_NAME,
                RabbitMQConfig.ROUTING_KEY_RESERVATION_STATUS_CHANGED,
                statusEvent
        );
        
        log.info("Reservation status changed event published: reservationId={}, {} -> {}", 
                reservation.getId(), currentStatus, newStatus);
        
        return toResponse(reservation);
    }

    /**
     * Проверяет, допустим ли переход между статусами
     */
    private boolean isValidStatusTransition(String currentStatus, String newStatus) {
        // Нормализуем статусы (приводим к верхнему регистру)
        String current = currentStatus != null ? currentStatus.toUpperCase() : "";
        String next = newStatus != null ? newStatus.toUpperCase() : "";

        // PENDING может перейти в CONFIRMED, PAID или CANCELLED
        if ("PENDING".equals(current)) {
            return "CONFIRMED".equals(next) || "PAID".equals(next) || "CANCELLED".equals(next);
        }
        
        // CONFIRMED может перейти в PAID или CANCELLED
        if ("CONFIRMED".equals(current)) {
            return "PAID".equals(next) || "CANCELLED".equals(next);
        }
        
        // PAID может перейти только в CANCELLED
        if ("PAID".equals(current)) {
            return "CANCELLED".equals(next);
        }
        
        // CANCELLED - финальный статус, нельзя изменить
        if ("CANCELLED".equals(current)) {
            return false;
        }
        
        // Для неизвестных статусов разрешаем переход (на случай расширения)
        return true;
    }

    /**
     * Автоматически отменяет бронирования со статусом PENDING, которые не были подтверждены за 24 часа
     */
    public int cancelExpiredPendingReservations() {
        List<Reservation> pendingReservations = reservationRepository.findByStatus("PENDING");
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expirationTime = now.minusHours(24); // 24 часа назад
        int cancelledCount = 0;

        for (Reservation reservation : pendingReservations) {
            // Проверяем, прошло ли 24 часа с момента создания
            LocalDateTime createdAt = reservation.getCreatedAt();
            if (createdAt == null) {
                // Fallback: если createdAt не установлен, используем reservationTime
                createdAt = reservation.getReservationTime().minus(RESERVATION_DURATION);
            }
            
            if (createdAt.isBefore(expirationTime)) {
                try {
                    changeStatus(reservation.getId(), "CANCELLED");
                    cancelledCount++;
                    log.info("Автоматически отменено просроченное бронирование: reservationId={}, createdAt={}", 
                            reservation.getId(), createdAt);
                } catch (Exception e) {
                    log.error("Ошибка при автоматической отмене бронирования: reservationId={}", 
                            reservation.getId(), e);
                }
            }
        }

        if (cancelledCount > 0) {
            log.info("Автоматически отменено {} просроченных бронирований", cancelledCount);
        }
        
        return cancelledCount;
    }

    public void delete(Long id) {
        Reservation reservation = reservationRepository.findById(id);
        if (reservation == null) {
            throw new ResourceNotFoundException("Reservation", id);
        }

// Сохраняем ID перед удалением
        Long reservationId = reservation.getId();

        reservationRepository.delete(id);


// Публикуем событие после успешного удаления
        ReservationDeletedEvent event = new ReservationDeletedEvent(reservationId);

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EXCHANGE_NAME,
                RabbitMQConfig.ROUTING_KEY_RESERVATION_DELETED,
                event
        );


    }

    private ReservationResponse toResponse(Reservation reservation) {
        Client clientEntity = reservation.getClient();
        TableEntity tableEntity = reservation.getTable();

        ClientResponse client = new ClientResponse(clientEntity.getId(), clientEntity.getName(), clientEntity.getEmail(), clientEntity.getPhone());
        
        // Обрабатываем null тип стола - устанавливаем "STANDARD" по умолчанию
        String tableType = tableEntity.getType() != null && !tableEntity.getType().isBlank() 
                ? tableEntity.getType() 
                : "STANDARD";
        
        TableResponse table = new TableResponse(tableEntity.getId(), tableEntity.getNumber(), tableEntity.getNumberOfSeats(), tableType, tableEntity.isAvailable());

        return new ReservationResponse(reservation.getId(), client, table, reservation.getReservationTime(), reservation.getNumberOfGuests(), reservation.getStatus(), reservation.getPrice());
    }


}