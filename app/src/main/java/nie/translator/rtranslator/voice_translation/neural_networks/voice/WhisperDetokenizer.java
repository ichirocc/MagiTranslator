/*
 * Copyright 2026 MagiTranslator contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nie.translator.rtranslator.voice_translation.neural_networks.voice;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Whisper token ids -> text, in Java. Does what the BpeDecoder custom operator of Whisper_detokenizer.onnx
 * did through onnxruntime-extensions, whose native libraries are not 16 KB page aligned (Android 15+),
 * with the same data: the vocabulary, byte table, added tokens and special ids are read from the
 * attributes of that file (the one the app downloads), so nothing new is shipped.
 *
 * Same output as BpeDecoder (onnxruntime-extensions, every single token and 2003 random sequences): special
 * ids skipped, added tokens (timestamps) as their text, vocabulary tokens through the byte table, the
 * bytes as UTF-8, cut at a NUL byte (BpeDecoder's string ended there). Unlike BpeDecoder, an incomplete
 * UTF-8 sequence (the token limit was hit inside a character) becomes U+FFFD instead of invalid bytes.
 * Pure Java (tested on a PC).
 */
public final class WhisperDetokenizer {
    private final String[] vocab;
    private final int[] byteOf = new int[0x10000];   // vocabulary character -> byte, -1 = none
    private final Map<Integer, String> added = new HashMap<>();
    private final Set<Integer> special = new HashSet<>();
    private final boolean skipSpecial;

    private WhisperDetokenizer(Map<String, byte[]> s, Map<String, Long> i) throws IOException {
        if (!s.containsKey("id_vocab") || !s.containsKey("byte_decoder")) throw new IOException("not a BpeDecoder");
        vocab = new String(s.get("id_vocab"), StandardCharsets.UTF_8).split("\n", -1);
        java.util.Arrays.fill(byteOf, -1);
        for (String line : new String(s.get("byte_decoder"), StandardCharsets.UTF_8).split("\n")) {
            int tab = line.indexOf('\t');
            if (tab > 0) byteOf[Integer.parseInt(line.substring(0, tab))] = Integer.parseInt(line.substring(tab + 1));
        }
        if (s.containsKey("added_tokens")) {
            for (String line : new String(s.get("added_tokens"), StandardCharsets.UTF_8).split("\n")) {
                int tab = line.indexOf('\t');
                if (tab > 0) added.put(Integer.parseInt(line.substring(0, tab)), line.substring(tab + 1));
            }
        }
        if (s.containsKey("all_special_ids")) {
            for (String line : new String(s.get("all_special_ids"), StandardCharsets.UTF_8).split("\n")) {
                if (!line.isEmpty()) special.add(Integer.parseInt(line.trim()));
            }
        }
        Long skip = i.get("skip_special_tokens");
        skipSpecial = skip != null && skip != 0;
    }

    /** Reads the BpeDecoder node of Whisper_detokenizer.onnx. */
    public static WhisperDetokenizer load(File onnx) throws IOException {
        return fromModel(Files.readAllBytes(onnx.toPath()));
    }

    public static WhisperDetokenizer fromModel(byte[] model) throws IOException {
        Proto m = new Proto(model, 0, model.length);
        while (m.next()) {
            if (m.field != 7 || m.wire != 2) { m.skip(); continue; }   // ModelProto.graph
            Proto g = m.sub();
            while (g.next()) {
                if (g.field != 1 || g.wire != 2) { g.skip(); continue; }   // GraphProto.node
                Proto n = g.sub();
                String opType = null;
                Map<String, byte[]> strings = new HashMap<>();
                Map<String, Long> ints = new HashMap<>();
                while (n.next()) {
                    if (n.field == 4 && n.wire == 2) opType = new String(n.bytes(), StandardCharsets.UTF_8);   // op_type
                    else if (n.field == 5 && n.wire == 2) {   // AttributeProto
                        Proto a = n.sub();
                        String name = null; byte[] sv = null; Long iv = null;
                        while (a.next()) {
                            if (a.field == 1 && a.wire == 2) name = new String(a.bytes(), StandardCharsets.UTF_8);
                            else if (a.field == 4 && a.wire == 2) sv = a.bytes();
                            else if (a.field == 3 && a.wire == 0) iv = a.varint();
                            else a.skip();
                        }
                        if (name != null && sv != null) strings.put(name, sv);
                        if (name != null && iv != null) ints.put(name, iv);
                    } else n.skip();
                }
                if ("BpeDecoder".equals(opType)) return new WhisperDetokenizer(strings, ints);
            }
        }
        throw new IOException("no BpeDecoder node");
    }

    public String decode(int[] ids) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int id : ids) {
            if (skipSpecial && special.contains(id)) continue;
            String a = added.get(id);
            if (a != null) {
                byte[] b = a.getBytes(StandardCharsets.UTF_8);
                out.write(b, 0, b.length);
                continue;
            }
            if (id < 0 || id >= vocab.length) continue;
            String t = vocab[id];
            for (int k = 0; k < t.length(); k++) {
                int b = byteOf[t.charAt(k)];
                if (b >= 0) out.write(b);
            }
        }
        byte[] bytes = out.toByteArray();
        int end = 0;
        while (end < bytes.length && bytes[end] != 0) end++;   // BpeDecoder's C string ended at a NUL
        try {
            CharsetDecoder d = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
            return d.decode(ByteBuffer.wrap(bytes, 0, end)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return new String(bytes, 0, end, StandardCharsets.UTF_8);
        }
    }

    public int vocabularySize() {
        return vocab.length;
    }

    /** The few protobuf wire-format reads the ONNX file needs (no protobuf library). */
    private static final class Proto {
        private final byte[] b;
        private int p;
        private final int end;
        int field, wire;

        Proto(byte[] b, int start, int end) { this.b = b; this.p = start; this.end = end; }

        boolean next() throws IOException {
            if (p >= end) return false;
            long tag = varint();
            field = (int) (tag >>> 3);
            wire = (int) (tag & 7);
            return true;
        }

        long varint() throws IOException {
            long v = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (p >= end) throw new IOException("truncated");
                int x = b[p++] & 0xff;
                v |= (long) (x & 0x7f) << shift;
                if (x < 0x80) return v;
            }
            throw new IOException("bad varint");
        }

        private int length() throws IOException {
            long n = varint();
            if (n < 0 || p + n > end) throw new IOException("bad length");
            return (int) n;
        }

        byte[] bytes() throws IOException {
            int n = length();
            byte[] r = java.util.Arrays.copyOfRange(b, p, p + n);
            p += n;
            return r;
        }

        Proto sub() throws IOException {
            int n = length();
            Proto s = new Proto(b, p, p + n);
            p += n;
            return s;
        }

        void skip() throws IOException {
            switch (wire) {
                case 0: varint(); break;
                case 1: p += 8; break;
                case 2: { int n = length(); p += n; break; }   // not p += length(): Java reads p before length() moves it
                case 5: p += 4; break;
                default: throw new IOException("wire type " + wire);
            }
            if (p > end) throw new IOException("truncated");
        }
    }
}
