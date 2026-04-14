package com.verinews.backend.dto;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class AnalisisResponseDTO {
    private Long id;
    private Double scoreGlobal;
    private String resumen;
    private LocalDateTime fechaGeneracion;
    private List<MetricaDTO> metricas;
}