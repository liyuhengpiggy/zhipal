import cn.seniorvoice.demo.CommandParser;
import cn.seniorvoice.demo.LocalTranscript;
import cn.seniorvoice.demo.TranscriptCollector;
import cn.seniorvoice.demo.WavPcmReader;
import java.io.File;
import java.text.Normalizer;
import java.util.Locale;
import kotlin.Unit;
import org.vosk.LibVosk;
import org.vosk.Model;
import org.vosk.Recognizer;

/** Uses free-form recognition of synthetic speech; never starts activities or makes phone calls. */
public final class AppLaunchAsrSmoke {
    private static final String[] PHRASES = {
        "打开QQ", "打开扣扣", "打开微信", "打开浏览器", "打开抖音", "打开相机",
        "不要打开微信", "打开微信然后打开QQ"
    };

    private static String canonical(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{P}]", "")
            .replace("扣扣", "qq");
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("MODEL_DIRECTORY FIXTURE_DIRECTORY [--recognition-only]");
        boolean recognitionOnly = args.length > 2 && "--recognition-only".equals(args[2]);
        LibVosk.vosk_set_log_level(-1);
        int failures = 0;
        try (Model model = new Model(args[0])) {
            for (int index = 0; index < PHRASES.length; index++) {
                File wav = new File(args[1], "fixture-" + index + ".wav");
                TranscriptCollector collector = new TranscriptCollector();
                try (Recognizer recognizer = new Recognizer(model, 16000f)) {
                    recognizer.setWords(true);
                    WavPcmReader.INSTANCE.stream16k(wav, WavPcmReader.INSTANCE.inspect(wav),
                        percent -> Unit.INSTANCE, (pcm, count) -> {
                            if (recognizer.acceptWaveForm(pcm, count)) collector.append(recognizer.getResult());
                            return Unit.INSTANCE;
                        });
                    collector.append(recognizer.getFinalResult());
                }
                LocalTranscript transcript = collector.finish();
                Object parsed = CommandParser.INSTANCE.parse(transcript.getText());
                String command = parsed.getClass().getSimpleName();
                boolean positive = index < 6;
                boolean targetCorrect = canonical(PHRASES[index]).equals(canonical(transcript.getText()));
                boolean parsedCorrect = positive
                    ? !"Unknown".equals(command) && !"Call".equals(command)
                    : "Unknown".equals(command);
                boolean pass = positive
                    ? targetCorrect && transcript.getCanExecute() && (recognitionOnly || parsedCorrect)
                    : targetCorrect && (recognitionOnly || parsedCorrect);
                System.out.println((pass ? "PASS" : "FAIL") + " synthetic=" + PHRASES[index]
                    + " transcript=" + transcript.getText() + " score=" + transcript.getConfidence()
                    + " thresholdPassed=" + transcript.getCanExecute() + " parser=" + command
                    + " targetCorrect=" + targetCorrect);
                if (!pass) failures++;
            }
        }
        System.out.println("Synthetic ASR: " + (PHRASES.length - failures) + "/" + PHRASES.length
            + " passed; no Android, BLE, human recording or activity launch was exercised.");
        if (failures > 0) throw new IllegalStateException(failures + " synthetic recognition checks failed");
    }
}
