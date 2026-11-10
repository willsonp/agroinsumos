package com.agroinsumos.backend.menu;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface SubMenuRepository extends JpaRepository<SubMenu, Long> {

    @Query("select s from SubMenu s join fetch s.mainMenu m where s.estado = 'A' and m.estado = 'A' order by m.orden, s.orden")
    List<SubMenu> findActivos();
}
