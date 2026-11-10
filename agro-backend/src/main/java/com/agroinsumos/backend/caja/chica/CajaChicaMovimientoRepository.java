package com.agroinsumos.backend.caja.chica;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CajaChicaMovimientoRepository extends JpaRepository<CajaChicaMovimiento, Long> {
    Optional<CajaChicaMovimiento> findByIdAndCompaniaId(Long id, Long companiaId);

    @Query("""
            select m from CajaChicaMovimiento m where m.cajaChicaId = :fondo
              and (cast(:desde as date) is null or m.fecha >= :desde) and (cast(:hasta as date) is null or m.fecha <= :hasta)
             order by m.fecha desc, m.numero desc
            """)
    List<CajaChicaMovimiento> buscar(Long fondo, LocalDate desde, LocalDate hasta);

    /** Gastos vigentes aún no incluidos en una reposición. */
    @Query("""
            select m from CajaChicaMovimiento m where m.cajaChicaId = :fondo and m.tipo = 'GASTO' and m.estado = 'REGISTRADO'
               and m.reposicionId is null order by m.fecha, m.numero
            """)
    List<CajaChicaMovimiento> pendientesDeReponer(Long fondo);

    List<CajaChicaMovimiento> findByReposicionIdOrderByFechaAscNumeroAsc(Long reposicionId);

    /** El NCF de un suplidor solo se registra una vez (caja chica o compras): evita líneas duplicadas en el 606. */
    @Query(nativeQuery = true, value = """
            select exists (select 1 from caja_chica_movimiento m where m.compania_id = :cia and m.ncf = :ncf and m.rnc = :rnc and m.estado = 'REGISTRADO')
                or exists (select 1 from compra c join proveedor p on p.id = c.proveedor_id
                            where c.compania_id = :cia and c.ncf_proveedor = :ncf and p.rnc_cedula = :rnc and c.estado <> 'ANULADA')
            """)
    boolean ncfRegistrado(Long cia, String rnc, String ncf);
}
