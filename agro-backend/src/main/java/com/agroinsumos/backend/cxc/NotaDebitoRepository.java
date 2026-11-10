package com.agroinsumos.backend.cxc;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface NotaDebitoRepository extends JpaRepository<NotaDebito, Long> {
    Optional<NotaDebito> findByIdAndCompaniaId(Long id, Long companiaId);
}
