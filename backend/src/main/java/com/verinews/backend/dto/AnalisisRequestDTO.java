package com.verinews.backend.dto;

import com.verinews.backend.models.enums.TipoEntrada;

import lombok.Data;

@Data
public class AnalisisRequestDTO {

    private String contenido;
    private TipoEntrada tipoEntrada;
}