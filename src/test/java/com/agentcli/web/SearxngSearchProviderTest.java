package com.agentcli.web;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearxngSearchProviderTest {

    private MockWebServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void parsesResultsAndRespectsTopK() throws Exception {
        String json = """
                {"results":[
                  {"title":"First","url":"https://a.example.com/p","content":"alpha snippet"},
                  {"title":"Second","url":"https://b.example.com/q","content":"beta snippet"},
                  {"title":"Third","url":"https://c.example.com/r","content":"gamma snippet"}
                ]}
                """;
        server.enqueue(new MockResponse().setBody(json).addHeader("Content-Type", "application/json"));

        SearxngSearchProvider provider = new SearxngSearchProvider(server.url("/").toString());
        List<SearchResult> results = provider.search("test", 2);

        assertEquals(2, results.size());
        assertEquals(1, results.get(0).position());
        assertEquals("First", results.get(0).title());
        assertEquals("a.example.com", results.get(0).source());
        assertEquals("beta snippet", results.get(1).snippet());
    }

    @Test
    void blankBaseUrlIsNotReady() {
        SearxngSearchProvider provider = new SearxngSearchProvider("  ");
        assertFalse(provider.isReady());
        assertTrue(provider.unavailableHint().contains("SEARXNG_URL"));
    }
}
