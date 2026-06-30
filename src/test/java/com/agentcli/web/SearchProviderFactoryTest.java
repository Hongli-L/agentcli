package com.agentcli.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SearchProviderFactoryTest {

    @Test
    void explicitProviderWins() {
        assertEquals("searxng",
                SearchProviderFactory.pickProvider("searxng", "glm-key", "serp-key", "http://x"));
        assertEquals("serpapi",
                SearchProviderFactory.pickProvider("SerpApi", "glm-key", null, null));
    }

    @Test
    void glmKeyPrefersZhipu() {
        assertEquals("zhipu",
                SearchProviderFactory.pickProvider(null, "glm-key", "serp-key", "http://x"));
    }

    @Test
    void serpKeyWhenNoGlm() {
        assertEquals("serpapi",
                SearchProviderFactory.pickProvider(null, null, "serp-key", "http://x"));
    }

    @Test
    void searxngWhenOnlyUrl() {
        assertEquals("searxng",
                SearchProviderFactory.pickProvider(null, null, null, "http://localhost:8888"));
    }

    @Test
    void defaultsToZhipuPlaceholder() {
        assertEquals("zhipu",
                SearchProviderFactory.pickProvider(null, null, null, null));
    }
}
