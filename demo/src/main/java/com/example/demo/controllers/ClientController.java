package com.example.demo.controllers;


import com.example.Restaurant.dto.ClientRequest;
import com.example.Restaurant.dto.ClientResponse;
import com.example.Restaurant.endpoints.ClientApi;
import com.example.demo.assembler.ClientModelAssembler;

import com.example.demo.service.ClientService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import org.springframework.hateoas.CollectionModel;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
public class ClientController implements ClientApi {
    private final ClientService clientService;
    private final ClientModelAssembler assembler;
    private final Counter clientRequestsCounter;
    private final Timer clientOperationsTimer;

    public ClientController(ClientService clientService, ClientModelAssembler assembler,
                           Counter clientRequestsCounter, Timer clientOperationsTimer) {
        this.clientService = clientService;
        this.assembler = assembler;
        this.clientRequestsCounter = clientRequestsCounter;
        this.clientOperationsTimer = clientOperationsTimer;
    }

    @Override
    public CollectionModel<EntityModel<ClientResponse>> getAllClients() {
        return clientOperationsTimer.recordCallable(() -> {
            clientRequestsCounter.increment();
            return assembler.toCollectionModel(clientService.findAll());
        });
    }

    @Override
    public EntityModel<ClientResponse> getClientById(Long id) {
        return assembler.toModel(clientService.findById(id));
    }

    @Override
    public ResponseEntity<EntityModel<ClientResponse>> createClient(ClientRequest request) {
        return clientOperationsTimer.recordCallable(() -> {
            clientRequestsCounter.increment();
            ClientResponse created = clientService.create(request);
            EntityModel<ClientResponse> model = assembler.toModel(created);
            return ResponseEntity.created(model.getRequiredLink("self").toUri())
                    .body(model);
        });
    }

    @Override
    public EntityModel<ClientResponse> updateClient(Long id, ClientRequest request) {
        return assembler.toModel(clientService.update(id, request));
    }

    @Override
    public void deleteClient(Long id) {
        clientService.delete(id);
    }
}