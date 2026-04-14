package com.verinews.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.verinews.backend.models.*;
import com.verinews.backend.models.enums.TipoEntrada;
import com.verinews.backend.models.enums.TipoMetrica;
import com.verinews.backend.repositories.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class AnalisisService {

    @Value("${groq.api.key}")
    private String groqApiKey;

    private final SolicitudAnalisisRepository solicitudRepo;
    private final ResultadoAnalisisRepository resultadoRepo;
    private final MetricaRepository metricaRepo;

    public AnalisisService(SolicitudAnalisisRepository solicitudRepo,
                           ResultadoAnalisisRepository resultadoRepo,
                           MetricaRepository metricaRepo) {
        this.solicitudRepo = solicitudRepo;
        this.resultadoRepo = resultadoRepo;
        this.metricaRepo = metricaRepo;
    }

    public record AnalisisCompleto(ResultadoAnalisis resultado, List<Metrica> metricas) {}

    public AnalisisCompleto analizar(String contenido, TipoEntrada tipoEntrada) {

        // 1. Guardar la solicitud en BD
        SolicitudAnalisis solicitud = new SolicitudAnalisis();
        solicitud.setContenido(contenido);
        solicitud.setTipoEntrada(tipoEntrada);
        solicitud.setFechaSolicitud(LocalDateTime.now());
        solicitud.setUsuario(null);
        solicitudRepo.save(solicitud);

        // 2. Llamar a Groq y obtener las 7 métricas
        List<Metrica> metricas = llamarGroq(contenido);

        // 3. Calcular el IMI
        double scoreGlobal = calcularIMI(metricas);

        // 4. Guardar el resultado en BD
        ResultadoAnalisis resultado = new ResultadoAnalisis();
        resultado.setSolicitud(solicitud);
        resultado.setScoreGlobal(scoreGlobal);
        resultado.setResumen(generarResumen(metricas));
        resultado.setFechaGeneracion(LocalDateTime.now());
        resultadoRepo.save(resultado);

        // 5. Asignar el resultado a cada métrica y guardarlas
        for (Metrica m : metricas) {
            m.setResultado(resultado);
            metricaRepo.save(m);
        }

        return new AnalisisCompleto(resultado, metricas);
    }

    private List<Metrica> llamarGroq(String contenido) {
        try {
            URL url = new URL("https://api.groq.com/openai/v1/chat/completions");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + groqApiKey);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            String systemPrompt = "Eres un asistente experto en analisis de credibilidad de noticias y textos informativos. Analiza el texto que te proporciona el usuario y evalua las siguientes 7 metricas de credibilidad.\\n\\nDevuelve SOLO un JSON con esta estructura exacta, sin ningun texto adicional:\\n{\\n  \\\"semantica\\\": {\\\"alertas\\\": number, \\\"observaciones\\\": string, \\\"fragmentos_detectados\\\": string[]},\\n  \\\"verificacion_factual\\\": {\\\"alertas\\\": number, \\\"observaciones\\\": string, \\\"fragmentos_detectados\\\": string[]},\\n  \\\"sesgo\\\": {\\\"alertas\\\": number, \\\"observaciones\\\": string, \\\"fragmentos_detectados\\\": string[]},\\n  \\\"fuentes\\\": {\\\"alertas\\\": number, \\\"observaciones\\\": string, \\\"fragmentos_detectados\\\": string[]},\\n  \\\"consistencia\\\": {\\\"alertas\\\": number, \\\"observaciones\\\": string, \\\"fragmentos_detectados\\\": string[]},\\n  \\\"tono\\\": {\\\"alertas\\\": number, \\\"observaciones\\\": string, \\\"fragmentos_detectados\\\": string[]},\\n  \\\"cifras\\\": {\\\"alertas\\\": number, \\\"observaciones\\\": string, \\\"fragmentos_detectados\\\": string[]}\\n}\\n\\nReglas estrictas:\\n- El campo alertas de cada metrica es un numero entero entre 0 y 5. Nunca superes 5.\\n- El campo observaciones resume los hallazgos en maximo 2-3 frases.\\n- El campo fragmentos_detectados contiene frases textuales extraidas del texto original. Si no hay fragmentos relevantes, devuelve [].\\n- Las 7 metricas siguen exactamente la misma estructura. No añadas ni elimines campos.\\n- Para cifras, usa SIEMPRE los campos alertas, observaciones y fragmentos_detectados. Nunca uses numeros_detectados ni posible_manipulacion.\\n- Devuelve unicamente el JSON, sin bloques de codigo, sin explicaciones, sin texto extra.";

            String requestBody = "{"
                    + "\"model\": \"llama-3.3-70b-versatile\","
                    + "\"messages\": ["
                    + "{\"role\": \"system\", \"content\": \"" + systemPrompt + "\"},"
                    + "{\"role\": \"user\", \"content\": \"" + contenido.replace("\"", "\\\"") + "\"}"
                    + "],"
                    + "\"temperature\": 0.3,"
                    + "\"max_tokens\": 1000"
                    + "}";

            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.getBytes("utf-8"));
            }

            BufferedReader br = new BufferedReader(new InputStreamReader(
                    conn.getResponseCode() < 300 ? conn.getInputStream() : conn.getErrorStream(), "utf-8"
            ));
            StringBuilder respuesta = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) respuesta.append(line);

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(respuesta.toString());
            String content = root.path("choices").get(0).path("message").path("content").asText();
            content = content.replace("'", "\"");

            return parsearMetricas(content, mapper);

        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    private List<Metrica> parsearMetricas(String json, ObjectMapper mapper) throws Exception {
        List<Metrica> metricas = new ArrayList<>();
        JsonNode root = mapper.readTree(json);

        String[] claves = {"semantica", "verificacion_factual", "sesgo", "fuentes", "consistencia", "tono", "cifras"};
        TipoMetrica[] tipos = {
            TipoMetrica.SEMANTICA,
            TipoMetrica.VERIFICACION_FACTUAL,
            TipoMetrica.SESGO,
            TipoMetrica.FUENTES,
            TipoMetrica.CONSISTENCIA,
            TipoMetrica.TONO,
            TipoMetrica.CIFRAS
        };

        for (int i = 0; i < claves.length; i++) {
            JsonNode nodo = root.path(claves[i]);
            Metrica m = new Metrica();
            m.setTipo(tipos[i]);
            m.setAlertas(nodo.path("alertas").asInt());
            m.setObservaciones(nodo.path("observaciones").asText());
            JsonNode fragmentos = nodo.path("fragmentos_detectados");
            m.setFragmentosDetectados(mapper.writeValueAsString(fragmentos));
            metricas.add(m);
        }

        return metricas;
    }

    private String generarResumen(List<Metrica> metricas) {
        if (metricas.isEmpty()) return "No se pudo analizar el contenido.";
        return metricas.stream()
                .max((a, b) -> Integer.compare(a.getAlertas(), b.getAlertas()))
                .orElse(metricas.get(0))
                .getObservaciones();
    }

    public double calcularIMI(List<Metrica> metricas) {
        final int MAX_ALERTAS = 5;
        double[] pesos = {0.20, 0.25, 0.20, 0.15, 0.10, 0.05, 0.05};
        double penalizacionTotal = 0.0;

        for (int i = 0; i < metricas.size(); i++) {
            int alertas = metricas.get(i).getAlertas();
            double penalizacion = Math.min((double) alertas / MAX_ALERTAS, 1.0);
            penalizacionTotal += penalizacion * pesos[i];
        }

        return 100 - (penalizacionTotal * 100);
    }
}