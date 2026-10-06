package com.aishorts.engine.drafts;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decide la calidad de una fuente por su dominio, sin confiar en lo que
 * diga el modelo. Las listas vienen de configuración: una entrada
 * "nasa.gov" cubre también sus subdominios; una entrada que empieza con
 * punto (".edu") es un sufijo. Las entradas explícitas ganan a los sufijos.
 */
public final class SourcePolicy {

    public static final String TIER_A = "A";
    public static final String TIER_B = "B";

    /** Segundos niveles típicos de dominios nacionales (bbc.co.uk, nhm.ac.uk, unam.edu.mx, ...). */
    private static final Set<String> SECOND_LEVEL_LABELS = Set.of("co", "ac", "gov", "gob", "edu", "org", "com", "net");

    private final List<String> tierA;
    private final List<String> tierB;
    private final List<String> orientationOnly;

    public SourcePolicy(List<String> tierA, List<String> tierB, List<String> orientationOnly) {
        this.tierA = lower(tierA);
        this.tierB = lower(tierB);
        this.orientationOnly = lower(orientationOnly);
    }

    public static SourcePolicy from(DraftsProperties properties) {
        return new SourcePolicy(properties.tierADomains(), properties.tierBDomains(), properties.orientationOnlyDomains());
    }

    /** "A", "B" o null si el dominio no tiene tier (desconocido, o solo de orientación como Wikipedia). */
    public String tierOf(String url) {
        String host = hostOf(url);
        if (host == null || matchesAny(host, orientationOnly, false)) {
            return null;
        }
        if (matchesAny(host, tierA, false)) return TIER_A;
        if (matchesAny(host, tierB, false)) return TIER_B;
        if (matchesAny(host, tierA, true)) return TIER_A;
        if (matchesAny(host, tierB, true)) return TIER_B;
        return null;
    }

    public boolean isOrientationOnly(String url) {
        String host = hostOf(url);
        return host != null && matchesAny(host, orientationOnly, false);
    }

    /**
     * Clave para decidir si dos fuentes son de dominios distintos:
     * science.nasa.gov y solarsystem.nasa.gov cuentan como el mismo
     * (nasa.gov).
     */
    public String domainKey(String url) {
        String host = hostOf(url);
        if (host == null) {
            return null;
        }
        for (List<String> list : List.of(tierA, tierB, orientationOnly)) {
            for (String entry : list) {
                if (!entry.startsWith(".") && (host.equals(entry) || host.endsWith("." + entry))) {
                    return entry;
                }
            }
        }
        String[] labels = host.split("\\.");
        int n = labels.length;
        if (n >= 3 && labels[n - 1].length() == 2 && SECOND_LEVEL_LABELS.contains(labels[n - 2])) {
            return labels[n - 3] + "." + labels[n - 2] + "." + labels[n - 1];
        }
        return n >= 2 ? labels[n - 2] + "." + labels[n - 1] : host;
    }

    /** Host en minúsculas y sin "www.", o null si la URL no es http(s) válida. */
    public static String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (host == null || scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                return null;
            }
            host = host.toLowerCase(Locale.ROOT);
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Forma canónica para comparar una URL citada con las que devolvió la
     * búsqueda: sin fragmento ni barra final, https, host en minúsculas y
     * sin "www.".
     */
    public static String canonicalUrl(String url) {
        if (url == null) {
            return null;
        }
        String trimmed = url.trim();
        try {
            URI uri = URI.create(trimmed);
            if (uri.getHost() == null || uri.getScheme() == null) {
                return trimmed;
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            while (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
            String port = uri.getPort() == -1 ? "" : ":" + uri.getPort();
            return "https://" + host + port + path + query;
        } catch (IllegalArgumentException e) {
            return trimmed;
        }
    }

    private static boolean matchesAny(String host, List<String> entries, boolean suffixes) {
        for (String entry : entries) {
            if (suffixes && entry.startsWith(".") && host.endsWith(entry)) {
                return true;
            }
            if (!suffixes && !entry.startsWith(".") && (host.equals(entry) || host.endsWith("." + entry))) {
                return true;
            }
        }
        return false;
    }

    private static List<String> lower(List<String> entries) {
        return entries == null ? List.of() : entries.stream().map(e -> e.trim().toLowerCase(Locale.ROOT)).toList();
    }
}
