package com.aishorts.engine.drafts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Un borrador "de oro" que cumple TODAS las reglas (cero violaciones duras
 * y blandas) con las URLs de RESEARCHED, como Map mutable: cada test rompe
 * una sola cosa y verifica que la regla correspondiente lo detecta.
 */
final class DraftFixtures {

    static final String NASA = "https://science.nasa.gov/solar-system/tunguska-event/";
    static final String BRITANNICA = "https://www.britannica.com/event/Tunguska-event";
    static final String USGS = "https://www.usgs.gov/programs/tunguska-blast";
    static final String SMITHSONIAN = "https://www.smithsonianmag.com/science-nature/tunguska-120/";
    static final List<String> RESEARCHED = List.of(NASA, BRITANNICA, USGS, SMITHSONIAN);

    static final String ANCHOR = "Hyperrealistic cinematic documentary footage, vertical 9:16 composition";
    static final String[] ROLES = {"GANCHO", "EXPLICACION", "CONTEXTO", "GIRO", "CONSECUENCIA", "CIERRE"};
    /** Dentro del rango de cada rol; total 94 (92-104), cada escena entre 3 y 8 s a 2.65 palabras/s. */
    static final int[] WORDS = {12, 19, 18, 15, 18, 12};

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DraftFixtures() {
    }

    /** Las propiedades reales de application.yml (app.drafts), para que los tests usen la misma configuración. */
    static DraftsProperties properties() {
        try {
            List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                    .load("application.yml", new ClassPathResource("application.yml"));
            Binder binder = new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources));
            return binder.bind("app.drafts", DraftsProperties.class).get();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Map<String, Object> validDraft(String slug, String topic) {
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("id", slug);
        draft.put("topic", topic);
        draft.put("title", "El estallido que tumbó un bosque entero");
        List<Object> scenes = new ArrayList<>();
        List<List<String>> claimIds = List.of(List.of("c1"), List.of("c1", "c2"), List.of("c2"), List.of("c3"), List.of("c3"), List.of());
        for (int i = 0; i < ROLES.length; i++) {
            Map<String, Object> scene = new LinkedHashMap<>();
            scene.put("id", slug + "-" + ROLES[i].toLowerCase());
            scene.put("role", ROLES[i]);
            scene.put("order", i + 1);
            scene.put("narrationText", words(WORDS[i]));
            scene.put("visualPrompt", ANCHOR + ", one continuous wide aerial shot over a dense taiga forest at dawn, soft volumetric light, realistic scale.");
            scene.put("visualConstraints", List.of("the forest reaches the horizon"));
            scene.put("claimIds", new ArrayList<>(claimIds.get(i)));
            scene.put("words", 99); // valor falso del modelo: el servidor lo recalcula
            scenes.add(scene);
        }
        draft.put("scenes", scenes);

        List<Object> claims = new ArrayList<>();
        claims.add(claim("c1", "HECHO", "Un objeto explotó sobre la taiga el 30 de junio de 1908.", NASA, BRITANNICA));
        claims.add(claim("c2", "ESTIMACION", "Se estima que derribó unos 80 millones de árboles.", USGS, SMITHSONIAN));
        Map<String, Object> c3 = claim("c3", "HIPOTESIS", "Sobre una gran ciudad, rompería vidrios a decenas de kilómetros.");
        c3.put("status", "SIN_VERIFICAR");
        claims.add(c3);
        draft.put("claims", claims);

        Map<String, Object> whatIf = new LinkedHashMap<>();
        whatIf.put("premise", "El mismo estallido, sobre una gran ciudad genérica.");
        whatIf.put("parametersFromReal", new ArrayList<>(List.of(new LinkedHashMap<>(Map.of(
                "name", "área derribada", "value", "unos 2000 km2", "claimId", "c2")))));
        whatIf.put("assumptions", List.of("misma altura de explosión"));
        whatIf.put("limits", "No estima víctimas.");
        draft.put("whatIf", whatIf);

        // checks falsos del modelo: el servidor los ignora y los recalcula
        draft.put("checks", new LinkedHashMap<>(Map.of("totalWords", 1, "estimatedSeconds", 1, "allFactsHaveClaims", false,
                "sourcesVerified", false, "violations", List.of("inventada por el modelo"))));
        draft.put("risks", List.of());
        return draft;
    }

    static Map<String, Object> claim(String id, String type, String text, String... urls) {
        Map<String, Object> claim = new LinkedHashMap<>();
        claim.put("id", id);
        claim.put("type", type);
        claim.put("text", text);
        claim.put("status", "VERIFICADO");
        claim.put("confidence", "ALTA");
        claim.put("disputed", false);
        List<Object> sources = new ArrayList<>();
        for (String url : urls) {
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("title", "Fuente");
            source.put("publisher", "Editor");
            source.put("url", url);
            source.put("accessed", "1999-01-01"); // el servidor pone la fecha real
            source.put("tier", "B");               // el servidor decide el tier por dominio
            sources.add(source);
        }
        claim.put("sources", sources);
        return claim;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> scenes(Map<String, Object> draft) {
        return (List<Map<String, Object>>) draft.get("scenes");
    }

    static Map<String, Object> scene(Map<String, Object> draft, int index) {
        return scenes(draft).get(index);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> claimById(Map<String, Object> draft, String id) {
        for (Object claim : (List<Object>) draft.get("claims")) {
            Map<String, Object> map = (Map<String, Object>) claim;
            if (id.equals(map.get("id"))) {
                return map;
            }
        }
        throw new IllegalArgumentException(id);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> whatIf(Map<String, Object> draft) {
        return (Map<String, Object>) draft.get("whatIf");
    }

    static String words(int n) {
        return String.join(" ", Collections.nCopies(n, "palabra"));
    }

    static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static String proposalsJson(String... topics) {
        List<Object> candidates = new ArrayList<>();
        for (String topic : topics) {
            Map<String, Object> candidate = new LinkedHashMap<>();
            candidate.put("topic", topic);
            candidate.put("realEvent", "Evento real de " + topic);
            candidate.put("whatIf", "¿Y si " + topic + " hubiera sido distinto?");
            candidate.put("keyFigures", List.of("cifra 1", "cifra 2"));
            candidate.put("sourcesAvailable", List.of("NASA", "USGS"));
            candidate.put("overlapWithExisting", false);
            candidate.put("risks", List.of());
            candidates.add(candidate);
        }
        return json(Map.of("candidates", candidates));
    }
}
