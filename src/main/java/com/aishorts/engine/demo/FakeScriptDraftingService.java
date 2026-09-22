package com.aishorts.engine.demo;

import com.aishorts.engine.domain.SceneRole;
import com.aishorts.engine.script.SceneDraft;
import com.aishorts.engine.script.ScriptDraftingService;
import com.aishorts.engine.script.StoryBrief;

import java.util.List;

/**
 * Devuelve el guion de Tunguska ya redactado, simulando lo que
 * ClaudeScriptDraftingService devolvería. Sirve para correr el demo sin
 * llamar a la API real (acá no hay credenciales configuradas).
 */
final class FakeScriptDraftingService implements ScriptDraftingService {

    @Override
    public List<SceneDraft> draftScenes(StoryBrief brief) {
        return List.of(
                new SceneDraft(SceneRole.GANCHO,
                        "Explotó en el cielo. No dejó cráter.",
                        "Bosque siberiano en calma al amanecer, corte súbito a destello blanco que satura el encuadre"),
                new SceneDraft(SceneRole.EXPLICACION,
                        "El objeto nunca llegó al suelo. Se desintegró en el aire, y la onda de la explosión arrasó el bosque en cuestión de segundos.",
                        "Objeto entrando en la atmósfera y desintegrándose, onda expansiva expandiéndose sobre el dosel del bosque"),
                new SceneDraft(SceneRole.CONTEXTO,
                        "30 de junio de 1908, Siberia. Ochenta millones de árboles derribados en más de 2.000 kilómetros cuadrados. Esto es Tunguska.",
                        "Mapa estilizado de Siberia con marcador de ubicación"),
                new SceneDraft(SceneRole.GIRO,
                        "Ahora cambia una sola cosa. Pon esa misma explosión sobre una ciudad.",
                        "Transición de estética a tinte de color distinto, texto en pantalla HIPÓTESIS"),
                new SceneDraft(SceneRole.CONSECUENCIA,
                        "La onda expansiva se propaga en todas direcciones. El daño depende de la altura de la explosión, su energía y lo que haya debajo.",
                        "Recreación de ciudad ficticia genérica, onda expansiva propagándose, daño estructural direccional sin víctimas visibles"),
                new SceneDraft(SceneRole.CIERRE,
                        "¿Qué pasaría si ocurriera hoy?",
                        "Vuelta al bosque real, fundido a negro con título")
        );
    }
}
