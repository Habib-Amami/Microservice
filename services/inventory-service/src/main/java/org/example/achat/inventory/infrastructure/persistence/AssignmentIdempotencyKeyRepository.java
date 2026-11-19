package org.example.achat.inventory.infrastructure.persistence;

import org.example.achat.inventory.domain.AssignmentIdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AssignmentIdempotencyKeyRepository extends JpaRepository<AssignmentIdempotencyKey, Long> {

    Optional<AssignmentIdempotencyKey> findByKeyValue(String keyValue);
}
