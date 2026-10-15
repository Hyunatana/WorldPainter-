package org.pepsoft.worldpainter.docking;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Bounded layout envelope. Validates XML before the backend can interpret its metadata. */
final class WorkspaceLayoutCodec {
    private WorkspaceLayoutCodec() { }

    static byte[] encode(byte[] xml) {
        if (xml.length + MAGIC.length > MAX_BYTES) throw new IllegalArgumentException("Workspace layout is too large");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(MAGIC);
        out.writeBytes(xml);
        return out.toByteArray();
    }

    static byte[] decode(InputStream in) throws Exception {
        byte[] data = in.readNBytes(MAX_BYTES + 1);
        if (data.length > MAX_BYTES || data.length <= MAGIC.length) throw new IllegalArgumentException("Invalid layout size");
        for (int i = 0; i < MAGIC.length; i++) if (data[i] != MAGIC[i]) throw new IllegalArgumentException("Unsupported layout format");
        byte[] xml = Arrays.copyOfRange(data, MAGIC.length, data.length);
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        var reader = factory.createXMLStreamReader(new ByteArrayInputStream(xml));
        int depth = 0, elements = 0;
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) throw new IllegalArgumentException("Layout entities are unsupported");
                if (event == XMLStreamConstants.START_ELEMENT) {
                    if (++depth > 128 || ++elements > 8192) throw new IllegalArgumentException("Layout is too complex");
                    if (elements == 1 && !reader.getLocalName().equals("app-layout")) throw new IllegalArgumentException("Invalid layout document");
                }
                if (event == XMLStreamConstants.END_ELEMENT) depth--;
            }
        } finally { reader.close(); }
        return xml;
    }

    static final int MAX_BYTES = 1_048_576;
    private static final byte[] MAGIC = "WorldPainter-ModernDocking:1\n".getBytes(StandardCharsets.US_ASCII);
}
