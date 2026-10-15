package org.pepsoft.worldpainter.docking;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class WorkspaceLayoutCodecTest {
    @Test public void preservesUtf8LayoutDataWithoutSharingMutableBuffers() throws Exception {
        byte[] xml = "<?xml version=\"1.0\"?><app-layout><layout title=\"Îles &amp; mountains\"/></app-layout>".getBytes(StandardCharsets.UTF_8);
        byte[] encoded = WorkspaceLayoutCodec.encode(xml);
        assertArrayEquals(xml, WorkspaceLayoutCodec.decode(new ByteArrayInputStream(encoded)));
        xml[0] = 0;
        assertEquals('<', WorkspaceLayoutCodec.decode(new ByteArrayInputStream(encoded))[0]);
    }

    @Test public void rejectsLegacyMalformedAndEntityDocumentsBeforeBackendParsing() {
        assertThrows(Exception.class, () -> WorkspaceLayoutCodec.decode(new ByteArrayInputStream(new byte[] {1, 2, 3})));
        for (String xml : new String[] {"<app-layout>", "<other/>",
                "<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///nonexistent'>]><app-layout>&e;</app-layout>"}) {
            assertThrows(Exception.class, () -> WorkspaceLayoutCodec.decode(new ByteArrayInputStream(WorkspaceLayoutCodec.encode(xml.getBytes(StandardCharsets.UTF_8)))));
        }
    }

    @Test public void rejectsOversizedDeepAndExcessiveElementDocuments() {
        assertThrows(IllegalArgumentException.class, () -> WorkspaceLayoutCodec.encode(new byte[WorkspaceLayoutCodec.MAX_BYTES]));
        assertThrows(Exception.class, () -> WorkspaceLayoutCodec.decode(new ByteArrayInputStream(new byte[WorkspaceLayoutCodec.MAX_BYTES + 1])));
        for (String xml : new String[] {"<app-layout>" + "<x>".repeat(129) + "</x>".repeat(129) + "</app-layout>",
                "<app-layout>" + "<x/>".repeat(8192) + "</app-layout>"}) {
            assertThrows(Exception.class, () -> WorkspaceLayoutCodec.decode(new ByteArrayInputStream(WorkspaceLayoutCodec.encode(xml.getBytes(StandardCharsets.UTF_8)))));
        }
    }
}
