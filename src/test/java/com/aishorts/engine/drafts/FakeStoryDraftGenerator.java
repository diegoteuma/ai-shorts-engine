package com.aishorts.engine.drafts;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * StoryDraftGenerator de tests: cero red. Devuelve respuestas encoladas en
 * orden y captura cada request que recibe, para poder afirmar qué le llegó
 * al "modelo" (existingStories, feedback de violaciones, nota de rechazo,
 * si se ofreció búsqueda web, ...). Una llamada sin respuesta encolada es un
 * error del test: falla en voz alta en vez de inventar algo.
 */
public final class FakeStoryDraftGenerator implements StoryDraftGenerator {

    private final Deque<Object> responses = new ArrayDeque<>();
    private final List<GeneratorRequest> requests = new ArrayList<>();

    public synchronized FakeStoryDraftGenerator enqueue(String text, List<String> researchedUrls) {
        responses.add(new GeneratorResult(text, researchedUrls, new DraftUsage(1000, 500, researchedUrls.isEmpty() ? 0 : 2), "end_turn"));
        return this;
    }

    public synchronized FakeStoryDraftGenerator enqueueFailure(RuntimeException failure) {
        responses.add(failure);
        return this;
    }

    public synchronized List<GeneratorRequest> requests() {
        return List.copyOf(requests);
    }

    public synchronized void reset() {
        responses.clear();
        requests.clear();
    }

    @Override
    public synchronized GeneratorResult generate(GeneratorRequest request) {
        requests.add(request);
        Object next = responses.poll();
        if (next == null) {
            throw new IllegalStateException("FakeStoryDraftGenerator: llamada inesperada (no hay respuesta encolada) en modo " + request.mode());
        }
        if (next instanceof RuntimeException failure) {
            throw failure;
        }
        return (GeneratorResult) next;
    }
}
