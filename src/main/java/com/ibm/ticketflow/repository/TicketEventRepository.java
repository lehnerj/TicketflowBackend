package com.ibm.ticketflow.repository;

import com.ibm.ticketflow.model.TicketEvent;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface TicketEventRepository extends CassandraRepository<TicketEvent, UUID> {
}
