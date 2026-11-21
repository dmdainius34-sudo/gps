package lt.example.gnss;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;

/**
 * Claude programoje: klausimas + programos ataskaita (fiksas, palydovai, kalibravimai, krypties įvykiai...) -> atsakymas.
 * Oficialus Anthropic Java SDK; API raktas – tik prietaise (SharedPreferences „claude“). Kviesti ne pagrindinėje gijoje.
 */
public final class ClaudeAsk {
    private ClaudeAsk() {}

    public static final String[][] MODELS = {
        {"claude-opus-5-5", "Claude Opus 5.5 (stipriausias)"},
        {"claude-sonnet-5-5", "Claude Sonnet 5.5 (greitesnis)"},
        {"claude-haiku-5-5", "Claude Haiku 5.5 (pigiausias)"},
    };

    private static final String SYSTEM =
        "Tu esi Android GNSS programos (lt.example.gnss) pagalbininkas, įmontuotas į pačią programą telefone ar planšetėje. " +
        "Naudotojas užduoda klausimą lietuviškai; kartu gauni programos ataskaitą su GNSS fiksu, palydovais, signalų stiprumu, " +
        "krypties kalibravimais pagal Saulę, Mėnulį ir žvaigždes, krypties įvykiais, taikiklio diagnostika ir kitais duomenimis. " +
        "Atsakyk lietuviškai, trumpai ir konkrečiai, remdamasis ataskaitos skaičiais (cituok juos). Jei duomenų nepakanka – pasakyk, ko trūksta " +
        "ir ką naudotojas galėtų padaryti programoje. Programos kodo keisti negali – jei reikia pakeitimų, pasiūlyk, ką paprašyti programos kūrėjo.";

    /** Grąžina atsakymo tekstą arba suprantamą klaidos pranešimą (niekada nemeta išimties). */
    public static String ask(String apiKey, String model, String question, String report) {
        if (apiKey == null || apiKey.trim().isEmpty()) return "Įveskite Anthropic API raktą (platform.claude.com) NLO lange.";
        AnthropicClient client = AnthropicOkHttpClient.builder().apiKey(apiKey.trim()).build();
        try {
            MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                .system(SYSTEM)
                .addUserMessage("Klausimas: " + question + "\n\n<programos_ataskaita>\n" + report + "\n</programos_ataskaita>")
                .build();
            Message r = client.messages().create(params);
            if (r.stopReason().isPresent() && "refusal".equals(r.stopReason().get().toString()))
                return "Claude į šį klausimą neatsakė (saugumo filtras). Pabandykite suformuluoti kitaip.";
            StringBuilder sb = new StringBuilder();
            for (ContentBlock b : r.content()) b.text().ifPresent(t -> { if (sb.length() > 0) sb.append("\n"); sb.append(t.text()); });
            if (r.stopReason().isPresent() && "max_tokens".equals(r.stopReason().get().toString())) sb.append("\n\n(atsakymas nukirptas – per ilgas)");
            return sb.length() == 0 ? "Claude atsakymas tuščias." : sb.toString();
        } catch (UnauthorizedException e) {
            return "Neteisingas API raktas (401). Patikrinkite raktą platform.claude.com.";
        } catch (PermissionDeniedException e) {
            return "Raktas neturi teisės naudoti šio modelio (403): " + e.getMessage();
        } catch (RateLimitException e) {
            return "Per daug užklausų arba baigėsi limitas (429). Pabandykite po minutės.";
        } catch (BadRequestException e) {
            return "Užklausos klaida (400): " + e.getMessage();
        } catch (AnthropicServiceException e) {
            return "Claude serverio klaida (" + e.statusCode() + "): " + e.getMessage();
        } catch (AnthropicIoException e) {
            return "Nėra ryšio su Claude (internetas?): " + e.getMessage();
        } catch (Exception e) {
            return "Klaida: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            try { client.close(); } catch (Exception ignored) {}
        }
    }
}
