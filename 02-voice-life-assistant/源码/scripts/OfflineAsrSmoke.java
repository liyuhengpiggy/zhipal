import cn.seniorvoice.demo.CommandParser;
import cn.seniorvoice.demo.LocalTranscript;
import cn.seniorvoice.demo.TranscriptCollector;
import cn.seniorvoice.demo.WavPcmReader;
import java.io.File;
import kotlin.Unit;
import org.vosk.LibVosk;
import org.vosk.Model;
import org.vosk.Recognizer;

/** Desktop synthetic-speech smoke test, not an Android/BLE or real-speaker acceptance test. */
public class OfflineAsrSmoke {
    public static void main(String[] args) throws Exception {
        LibVosk.vosk_set_log_level(-1);
        int failures = 0;
        String[] phrases = {"打开抖音", "给女儿打电话", "不要给女儿打电话", "不要打开抖音"};
        try (Model model = new Model(args[0])) {
            for (int index = 0; index < phrases.length; index++) {
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
                LocalTranscript result = collector.finish();
                Object parsed = CommandParser.INSTANCE.parse(result.getText());
                boolean expected = phrases[index].equals(result.getText());
                if (index < 2) expected &= result.getCanExecute();
                else expected = parsed instanceof CommandParser.Command.Unknown;
                System.out.println((expected ? "PASS" : "FAIL") + " synthetic=" + phrases[index]
                    + " transcript=" + result.getText() + " score=" + result.getConfidence() + " command=" + parsed);
                if (!expected) failures++;
            }
        }
        if (failures > 0) throw new IllegalStateException(failures + " synthetic recognition checks failed");
    }
}
