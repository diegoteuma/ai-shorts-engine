package com.aishorts.engine.drafts;

import com.aishorts.engine.domain.SceneRole;
import com.aishorts.engine.drafts.StoryDraft.Claim;
import com.aishorts.engine.drafts.StoryDraft.DraftScene;
import com.aishorts.engine.drafts.StoryDraft.Source;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Hechos derivados de un borrador que usan tanto DraftNormalizer (para
 * recalcular estados) como DraftValidator (para reportar violaciones), así
 * los dos aplican exactamente el mismo criterio. Todo es null-safe: el
 * borrador viene de un modelo y puede venir incompleto.
 */
public final class DraftRules {

    public static final List<SceneRole> EXPECTED_ROLES = List.of(
            SceneRole.GANCHO, SceneRole.EXPLICACION, SceneRole.CONTEXTO,
            SceneRole.GIRO, SceneRole.CONSECUENCIA, SceneRole.CIERRE);
    public static final Set<SceneRole> REAL_ROLES = Set.of(SceneRole.GANCHO, SceneRole.EXPLICACION, SceneRole.CONTEXTO);
    public static final Set<SceneRole> IF_ROLES = Set.of(SceneRole.GIRO, SceneRole.CONSECUENCIA);

    public static final String VERIFIED = "VERIFICADO";
    public static final String UNVERIFIED = "SIN_VERIFICAR";
    public static final String LOW_CONFIDENCE = "BAJA";
    public static final String HYPOTHESIS = "HIPOTESIS";
    public static final Set<String> REAL_CLAIM_TYPES = Set.of("HECHO", "ESTIMACION");

    private DraftRules() {
    }

    /** El SceneRole de la escena, o null si el texto no es un rol válido. */
    public static SceneRole roleOf(DraftScene scene) {
        if (scene == null || scene.role == null) {
            return null;
        }
        try {
            return SceneRole.valueOf(scene.role.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Escenas 1-3 (REAL). Null-safe: Set.of(...).contains(null) lanza NPE, y un rol inválido llega como null. */
    public static boolean isReal(SceneRole role) {
        return role != null && REAL_ROLES.contains(role);
    }

    /** Escenas 4-5 (IF). Null-safe, ver {@link #isReal}. */
    public static boolean isHypothetical(SceneRole role) {
        return role != null && IF_ROLES.contains(role);
    }

    public static List<DraftScene> scenes(StoryDraft draft) {
        return draft.scenes == null ? List.of() : draft.scenes;
    }

    public static List<Claim> claims(StoryDraft draft) {
        return draft.claims == null ? List.of() : draft.claims;
    }

    public static List<String> claimIds(DraftScene scene) {
        return scene == null || scene.claimIds == null ? List.of() : scene.claimIds;
    }

    public static List<Source> sources(Claim claim) {
        return claim == null || claim.sources == null ? List.of() : claim.sources;
    }

    /** Claims por id (la primera gana si el modelo repitió un id). */
    public static Map<String, Claim> claimsById(StoryDraft draft) {
        Map<String, Claim> byId = new LinkedHashMap<>();
        for (Claim claim : claims(draft)) {
            if (claim != null && claim.id != null) {
                byId.putIfAbsent(claim.id, claim);
            }
        }
        return byId;
    }

    /** Ids de claims referenciados por las escenas REAL (GANCHO, EXPLICACION, CONTEXTO). */
    public static Set<String> realClaimIds(StoryDraft draft) {
        Set<String> ids = new HashSet<>();
        for (DraftScene scene : scenes(draft)) {
            if (isReal(roleOf(scene))) {
                ids.addAll(claimIds(scene));
            }
        }
        return ids;
    }

    public static Set<String> canonicalUrls(Collection<String> urls) {
        if (urls == null) {
            return Set.of();
        }
        return urls.stream().filter(u -> u != null && !u.isBlank()).map(SourcePolicy::canonicalUrl).collect(Collectors.toSet());
    }

    public static boolean isResearched(String url, Set<String> canonicalResearched) {
        return url != null && canonicalResearched.contains(SourcePolicy.canonicalUrl(url));
    }

    /** Cuántos dominios distintos de tier A/B, entre las fuentes que sí aparecieron en la búsqueda, respaldan la claim. */
    public static int independentTierABDomains(Claim claim, Set<String> canonicalResearched, SourcePolicy policy) {
        Set<String> domains = new HashSet<>();
        for (Source source : sources(claim)) {
            if (source == null || !isResearched(source.url, canonicalResearched)) {
                continue;
            }
            if (policy.tierOf(source.url) != null) {
                domains.add(policy.domainKey(source.url));
            }
        }
        return domains.size();
    }

    /** true si la claim tiene fuentes y todas son de dominios solo de orientación (Wikipedia). */
    public static boolean onlyOrientationSources(Claim claim, SourcePolicy policy) {
        List<Source> sources = sources(claim);
        return !sources.isEmpty() && sources.stream().allMatch(s -> s != null && policy.isOrientationOnly(s.url));
    }

    public static boolean isHypothesis(Claim claim) {
        return claim != null && claim.type != null && HYPOTHESIS.equalsIgnoreCase(claim.type.trim());
    }

    /** Claims de hecho/estimación que quedaron SIN_VERIFICAR o con confianza BAJA: exigen acknowledgeUnverified al aprobar. */
    public static List<String> unverifiedOrLowConfidenceClaimIds(StoryDraft draft) {
        return claims(draft).stream()
                .filter(c -> c != null && !isHypothesis(c))
                .filter(c -> !VERIFIED.equals(c.status) || LOW_CONFIDENCE.equals(c.confidence))
                .map(c -> c.id)
                .toList();
    }
}
