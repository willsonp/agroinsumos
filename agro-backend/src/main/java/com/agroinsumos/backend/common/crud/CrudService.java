package com.agroinsumos.backend.common.crud;

import com.agroinsumos.backend.common.exception.NotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plantilla de CRUD para catálogos (Template Method). Cada catálogo solo define cómo
 * buscar, cómo mapear request -> entidad y entidad -> response, y sus validaciones.
 * Sustituye los ~45 Ctrl_* del Swing que repetían conexión, SQL y JOptionPane.
 */
@Transactional(readOnly = true)
public abstract class CrudService<E extends Catalogo, Q, R> {

    protected abstract JpaRepository<E, Long> repo();

    protected abstract String recurso();

    protected abstract E nueva(Long companiaId);

    protected abstract void aplicar(E entidad, Q request, Long companiaId);

    protected abstract R respuesta(E entidad);

    protected abstract Page<E> buscar(Long companiaId, String q, Pageable pageable);

    /** Validaciones de negocio (unicidad, referencias...). */
    protected void validar(E entidad, Q request, Long companiaId) {}

    public Page<R> listar(Long companiaId, String q, Pageable pageable) {
        return buscar(companiaId, (q == null || q.isBlank()) ? null : q.trim(), pageable).map(this::respuesta);
    }

    public R obtener(Long companiaId, Long id) {
        return respuesta(cargar(companiaId, id));
    }

    @Transactional
    public R crear(Long companiaId, Q request) {
        E e = nueva(companiaId);
        validar(e, request, companiaId);
        aplicar(e, request, companiaId);
        return respuesta(repo().save(e));
    }

    @Transactional
    public R actualizar(Long companiaId, Long id, Q request) {
        E e = cargar(companiaId, id);
        validar(e, request, companiaId);
        aplicar(e, request, companiaId);
        return respuesta(e);
    }

    @Transactional
    public void desactivar(Long companiaId, Long id) {
        cargar(companiaId, id).setEstado("I");
    }

    /** Carga verificando que la entidad pertenezca a la compañía del usuario. */
    public E cargar(Long companiaId, Long id) {
        return repo().findById(id)
                .filter(e -> e.getCompaniaId() == null || e.getCompaniaId().equals(companiaId))
                .orElseThrow(() -> new NotFoundException(recurso(), id));
    }

    /** Igual que cargar(), pero exige que esté activa (para usarla en documentos nuevos). */
    public E cargarActiva(Long companiaId, Long id) {
        E e = cargar(companiaId, id);
        if (!"A".equals(e.getEstado())) throw new NotFoundException(recurso() + " activo", id);
        return e;
    }
}
