package com.verinews.backend.controller;

import com.verinews.backend.dto.AnalisisRequestDTO;
import com.verinews.backend.dto.AnalisisResponseDTO;
import com.verinews.backend.dto.MetricaDTO;
import com.verinews.backend.service.AnalisisService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class AnalisisController {

    private final AnalisisService analisisService;

    public AnalisisController(AnalisisService analisisService) {
        this.analisisService = analisisService;
    }

    @PostMapping("/analizar")
    public ResponseEntity<AnalisisResponseDTO> analizar(@RequestBody AnalisisRequestDTO request) {

        AnalisisService.AnalisisCompleto completo = analisisService.analizar(
            request.getContenido(),
            request.getTipoEntrada()
        );

        AnalisisResponseDTO response = new AnalisisResponseDTO();
        response.setId(completo.resultado().getId());
        response.setScoreGlobal(completo.resultado().getScoreGlobal());
        response.setResumen(completo.resultado().getResumen());
        response.setFechaGeneracion(completo.resultado().getFechaGeneracion());

        List<MetricaDTO> metricasDTO = completo.metricas().stream()
            .map(m -> {
                MetricaDTO dto = new MetricaDTO();
                dto.setTipo(m.getTipo());
                dto.setAlertas(m.getAlertas());
                dto.setObservaciones(m.getObservaciones());
                dto.setFragmentosDetectados(m.getFragmentosDetectados());
                return dto;
            })
            .toList();

        response.setMetricas(metricasDTO);

        return ResponseEntity.ok(response);
    }
}