package nie.translator.rtranslator.plamo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import nie.translator.rtranslator.downloader.ModelSources;
import nie.translator.rtranslator.jane.Glossary;
import nie.translator.rtranslator.jane.JaNeTranslator;
import nie.translator.rtranslator.jane.PhraseBook;
import nie.translator.rtranslator.jane.PlamoPrompt;
import nie.translator.rtranslator.jane.PlamoTranslator;
import nie.translator.rtranslator.jane.TextCompletion;

/**
 * PLaMo path without the model (a scripted TextCompletion): PFML3 prompts, glossary terms, conversation
 * history, phrase book, review fallback, model files and license. Run: java nie.translator.rtranslator.plamo.PlamoTest
 */
public class PlamoTest {
    static int failures = 0;

    static void check(boolean ok, String what) {
        if (ok) System.out.println("ok   " + what);
        else { failures++; System.out.println("FAIL " + what); }
    }

    /** returns scripted outputs in order and records the prompts and slots */
    static final class Fake implements TextCompletion {
        final List<String> outputs = new ArrayList<>(), prompts = new ArrayList<>();
        final List<Integer> slots = new ArrayList<>();
        Fake(String... out) { outputs.addAll(Arrays.asList(out)); }
        @Override public String complete(String prompt, int maxTokens, String... stops) { return complete(-1, prompt, maxTokens, stops); }
        @Override public String complete(int slot, String prompt, int maxTokens, String... stops) {
            prompts.add(prompt);
            slots.add(slot);
            check(Arrays.asList(stops).contains("<|plamo:tag|>"), "stops at the next PFML3 element");
            return outputs.isEmpty() ? "" : outputs.remove(0);
        }
    }

    public static void main(String[] args) throws Exception {
        // ---- PFML3: the model card's glossary example, byte for byte ----
        Glossary card = new Glossary();
        card.put("管理画面", "dashboard");
        card.put("プロジェクト", "project");
        List<Glossary.Term> t = card.termsJaNe("プロジェクトの管理画面を開いてください。", null, 24);
        String p = PlamoPrompt.build("ja", "en", "プロジェクトの管理画面を開いてください。", Collections.emptyList(), t, false);
        check(p.equals("<|plamo:tag|>dataset<|plamo:msg|>translation<|plamo:tag|>glossary"
                + "<|plamo:index|>プロジェクト<|plamo:item|>project<|plamo:index|>管理画面<|plamo:item|>dashboard"
                + "<|plamo:tag|>input<|plamo:key|>lang<|plamo:val|>ja<|plamo:msg|>プロジェクトの管理画面を開いてください。"
                + "<|plamo:tag|>output<|plamo:key|>lang<|plamo:val|>en<|plamo:msg|>"), "glossary prompt as on the model card: " + p);

        // history before the glossary, glossary right before the input; polite only for Japanese output
        List<PlamoPrompt.Turn> h = Collections.singletonList(new PlamoPrompt.Turn("नमस्ते", "こんにちは"));
        String q = PlamoPrompt.build("ne", "ja", "धन्यवाद", h, Collections.emptyList(), true);
        check(q.equals("<|plamo:tag|>dataset<|plamo:msg|>translation"
                + "<|plamo:tag|>input<|plamo:key|>lang<|plamo:val|>ne<|plamo:msg|>नमस्ते"
                + "<|plamo:tag|>output<|plamo:key|>lang<|plamo:val|>ja<|plamo:msg|>こんにちは"
                + "<|plamo:tag|>input<|plamo:key|>lang<|plamo:val|>ne<|plamo:msg|>धन्यवाद"
                + "<|plamo:tag|>output<|plamo:key|>lang<|plamo:val|>ja<|plamo:key|>form<|plamo:val|>polite<|plamo:msg|>"), "history + polite: " + q);
        check(!PlamoPrompt.build("ja", "ne", "はい", null, null, true).contains("form"), "polite style only for Japanese output");
        check(PlamoPrompt.clean("a<|plamo:tag|>output<|plamo:msg|>b").equals("aoutputb"), "control markers removed from user text");

        // ---- glossary terms ----
        Glossary g = Glossary.withDefaults();
        g.put("車椅子", "ह्वीलचियर");
        g.put("椅子", "कुर्सी");
        g.putUser("移乗", "स्थानान्तरण");
        List<Glossary.Term> wc = g.termsJaNe("田中さんをベッドから車椅子に移乗します", null, 24);
        check(wc.size() == 2 && wc.get(0).source.equals("車椅子") && wc.get(1).source.equals("移乗"), "longest match, source order, no 椅子 inside 車椅子: " + wc);
        check(wc.get(0).required && !wc.get(1).required, "glossary terms required, user terms preferred");
        check(g.termsJaNe("日本語を勉強します", null, 24).toString().equals("[日本語=जापानी भाषा]"), "日本語 is one term, 日本 is not added: " + g.termsJaNe("日本語を勉強します", null, 24));
        Glossary many = new Glossary();
        for (int i = 0; i < 30; i++) many.putUser("語" + (char) ('ア' + i), "x" + i);
        many.put("東京", "टोकियो");
        StringBuilder src = new StringBuilder();
        for (int i = 0; i < 30; i++) src.append("語").append((char) ('ア' + i)).append("、");
        src.append("東京");
        List<Glossary.Term> lim = many.termsJaNe(src.toString(), null, 24);
        check(lim.size() == 24 && lim.get(lim.size() - 1).source.equals("東京"), "limit keeps required terms (東京 is last in the text)");
        List<Glossary.Term> ne = g.termsNeJa("म काठमाडौंमा बस्छु र नेपाली बोल्छु", 24);
        check(ne.toString().equals("[काठमाडौं=カトマンズ]"), "Nepali term with case marker, demonym नेपाली not reversed: " + ne);
        check(g.termsNeJa("काठमाडौँमा", 24).size() == 1, "ँ / ं spellings are the same term");
        check(g.termsNeJa("नेपालीहरू", 24).isEmpty(), "नेपाल not inside नेपाली");

        // ---- translator: phrase book, slots, history, review ----
        PhraseBook book = new PhraseBook();
        book.add("ありがとうございます。", "धन्यवाद।", false);
        Fake f = new Fake("तानाका जीलाई बेडबाट ह्वीलचियरमा सार्नेछु।", "車椅子に移ります。", "म ठीक छु।");
        PlamoTranslator tr = new PlamoTranslator(f, g);
        tr.setPhraseBook(book);
        JaNeTranslator.Result r0 = tr.translate("ありがとうございます", "ja", "ne");
        check(r0.text.equals("धन्यवाद।") && f.prompts.isEmpty(), "phrase book hit needs no model call");
        JaNeTranslator.Result r1 = tr.translate("田中さんをベッドから車椅子に移動します。", "ja", "ne");
        check(r1.text.equals("तानाका जीलाई बेडबाट ह्वीलचियरमा सार्नेछु।") && !r1.needsReview(), "ja->ne through the model: " + r1.text);
        check(f.slots.get(0) == 0 && f.prompts.get(0).contains("<|plamo:index|>車椅子<|plamo:item|>ह्वीलचियर"), "slot 0, glossary term in the prompt");
        check(f.prompts.get(0).contains("<|plamo:msg|>ありがとうございます<|plamo:tag|>output<|plamo:key|>lang<|plamo:val|>ne<|plamo:msg|>धन्यवाद।"), "the phrase-book turn is history (normalised source)");
        JaNeTranslator.Result r2 = tr.translate("ह्वीलचियरमा सर्छु।", "ne", "ja");
        check(r2.text.equals("車椅子に移ります。"), "ne->ja: " + r2.text);
        String p2 = f.prompts.get(1);
        check(f.slots.get(1) == 1 && p2.contains("<|plamo:val|>ne<|plamo:msg|>तानाका जीलाई बेडबाट ह्वीलचियरमा सार्नेछु।<|plamo:tag|>output<|plamo:key|>lang<|plamo:val|>ja<|plamo:msg|>田中さんをベッドから車椅子に移動します。"),
                "slot 1, the earlier ja->ne turn is history in the ne->ja direction");
        check(p2.contains("<|plamo:index|>ह्वीलचियर<|plamo:item|>車椅子"), "reverse glossary in ne->ja");
        check(tr.historySize() == 3, "three turns remembered");

        // a translation with a hard problem is retried without history and is not remembered
        Fake bad = new Fake("हो।", "म ३ बजे आउँछु।", "म ३ बजे आउँछु।");   // 2時 -> 3, with and without history
        PlamoTranslator tb = new PlamoTranslator(bad, Glossary.withDefaults());
        tb.translate("はい。", "ja", "ne");
        int before = tb.historySize();
        check(before == 1, "first turn remembered");
        JaNeTranslator.Result rb = tb.translate("2時に来ます。", "ja", "ne");
        check(rb.needsReview(), "wrong number is marked for review");
        check(bad.prompts.size() == 3 && bad.prompts.get(1).contains("हो।") && !bad.prompts.get(2).contains("हो।"), "retried once without history");
        check(tb.historySize() == before, "a translation marked for review is not remembered");

        // history grows to max, then is cut back to keep (so most prompts extend the previous one)
        Fake echo = new Fake();
        PlamoTranslator th = new PlamoTranslator(echo, Glossary.withDefaults());
        th.setHistory(2, 4);
        for (int i = 0; i < 5; i++) { echo.outputs.add("वाक्य " + (i + 1) + "।"); th.translate("文" + (i + 1) + "です。", "ja", "ne"); }
        check(th.historySize() == 2, "history cut back to 2 after exceeding 4: " + th.historySize());
        check(echo.prompts.get(3).startsWith(echo.prompts.get(2).substring(0, echo.prompts.get(2).indexOf("<|plamo:tag|>input<|plamo:key|>lang<|plamo:val|>ja<|plamo:msg|>文3"))),
                "between cuts each prompt starts with the previous history");
        th.clearConversation();
        check(th.historySize() == 0, "clearConversation forgets the history");

        // ---- model files and license ----
        File dir = Files.createTempDirectory("plamo").toFile();
        check(PlamoModels.usable(dir) == null, "nothing installed: not usable");
        File small = PlamoModels.file(dir, PlamoModels.Variant.SMALL);
        check(small.getParentFile().mkdirs(), "model dir");
        try (RandomAccessFile raf = new RandomAccessFile(small, "rw")) { raf.setLength(PlamoModels.Variant.SMALL.sizeBytes); }
        check(PlamoModels.isInstalled(dir, PlamoModels.Variant.SMALL), "installed by size");
        check(PlamoModels.usable(dir) == null, "license not accepted: not usable");
        PlamoModels.acceptLicense(dir);
        check(PlamoModels.isLicenseAccepted(dir) && small.equals(PlamoModels.usable(dir)), "accepted: usable");
        PlamoModels.choose(dir, PlamoModels.Variant.LARGE);
        check(small.equals(PlamoModels.usable(dir)), "chosen variant missing: the installed one is used");
        try (FileOutputStream o = new FileOutputStream(new File(PlamoModels.dir(dir), PlamoModels.ACCEPTED_FILE))) { o.write("2025-01-01".getBytes()); }
        check(!PlamoModels.isLicenseAccepted(dir), "acceptance of an older license text does not count");
        PlamoModels.revokeLicense(dir);
        check(PlamoModels.usable(dir) == null, "revoked: not usable");
        check(small.delete(), "cleanup");

        // personal adapter import: GGUF only, atomic, removable
        check(PlamoModels.adapter(dir) == null, "no adapter at first");
        byte[] fake = new byte[1000];
        fake[0] = 'G'; fake[1] = 'G'; fake[2] = 'U'; fake[3] = 'F';
        check(PlamoModels.importAdapter(new java.io.ByteArrayInputStream(fake), dir) == 1000 && PlamoModels.adapter(dir) != null, "GGUF adapter imported");
        boolean refused = false;
        try { PlamoModels.importAdapter(new java.io.ByteArrayInputStream("#!jane phrases\n".getBytes()), dir); } catch (java.io.IOException e) { refused = true; }
        check(refused && PlamoModels.adapter(dir).length() == 1000, "a dictionary file is not an adapter, the old adapter is kept");
        java.io.BufferedInputStream peek = new java.io.BufferedInputStream(new java.io.ByteArrayInputStream("#!jane phrases".getBytes()));
        check(!PlamoModels.looksLikeGguf(peek) && peek.read() == '#', "peeking does not consume the stream");
        PlamoModels.deleteAdapter(dir);
        check(PlamoModels.adapter(dir) == null, "adapter deleted");

        // pinned publisher download, not mirrored
        for (PlamoModels.Variant v : PlamoModels.Variant.values()) {
            String url = ModelSources.directUrl(v.fileName);
            check(url != null && url.startsWith("https://huggingface.co/pfnet/") && url.matches(".*/resolve/[0-9a-f]{40}/.*") && url.endsWith("/" + v.fileName), "pinned Hugging Face revision: " + v);
            check(ModelSources.expectedSha256(v.fileName) != null && ModelSources.expectedSha256(v.fileName).matches("[0-9a-f]{64}"), "SHA-256 pinned: " + v);
            check(!ModelSources.expectedHashes().containsKey(v.fileName), "not in the mirrored list (models.tsv): " + v);
        }

        // ---- drafts: the text as the model writes it, during the first model call only ----
        final class Streaming implements TextCompletion {
            final List<String> outputs = new ArrayList<>(Arrays.asList(
                    "दुई बजे आउँछु।",          // first call (no history yet): fine
                    "तीन बजे आउँछु।",          // next translation, with history: number lost -> retried
                    "दुई बजे आउँछु।",          // the retry without history
                    "हो।"));                    // a translation without a draft listener
            @Override public String complete(String prompt, int maxTokens, String... stops) { throw new AssertionError(); }
            @Override public String complete(int slot, String prompt, int maxTokens, java.util.function.Consumer<String> draft, String... stops) {
                String out = outputs.remove(0);
                if (draft != null) for (int k = 1; k <= out.length(); k++) draft.accept(out.substring(0, k));
                return out;
            }
            @Override public String complete(int slot, String prompt, int maxTokens, String... stops) {
                return complete(slot, prompt, maxTokens, (java.util.function.Consumer<String>) null, stops);
            }
        }
        PlamoTranslator ts = new PlamoTranslator(new Streaming(), Glossary.withDefaults());
        List<String> drafts = new ArrayList<>();
        ts.setDraftListener(drafts::add);
        JaNeTranslator.Result rs = ts.translate("2時に来ます。", "ja", "ne");
        check(!drafts.isEmpty() && drafts.get(drafts.size() - 1).equals("दुई बजे आउँछु।") && drafts.get(0).length() == 1, "drafts grow to the model text: " + drafts.size());
        check(rs.text.equals("दुई बजे आउँछु।"), "result unchanged by drafts");
        drafts.clear();
        JaNeTranslator.Result rs2 = ts.translate("2時に来ます。", "ja", "ne");
        check(drafts.size() == "तीन बजे आउँछु।".length() && drafts.get(drafts.size() - 1).equals("तीन बजे आउँछु।"),
                "drafts of the first call only, not of the retry: " + drafts.size());
        check(rs2.text.equals("दुई बजे आउँछु।"), "the checked result replaces the draft (retry kept the number)");
        ts.setDraftListener(null);
        drafts.clear();
        ts.translate("はい。", "ja", "ne");
        check(drafts.isEmpty(), "no listener, no drafts");

        // CPU features line of /proc/cpuinfo
        check(CpuFeatures.hasAll("fp asimd evtstrm aes asimddp sha512 i8mm bf16", "asimddp", "i8mm"), "features found");
        check(!CpuFeatures.hasAll("fp asimd asimddp", "asimddp", "i8mm"), "missing i8mm detected");
        check(!CpuFeatures.hasAll("fp asimd asimddpx i8mmx", "asimddp", "i8mm"), "whole words only");

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        if (failures > 0) System.exit(1);
    }
}
