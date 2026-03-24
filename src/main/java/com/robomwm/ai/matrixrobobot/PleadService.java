package com.robomwm.ai.matrixrobobot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Random;

public class PleadService {
    private final MatrixClient matrixClient;
    private final Path persistenceFile;
    private boolean isEnabled = false;
    private final Random random = new Random();

    private static final String[] THIRD_CHANCE_REACTIONS = {
        "a", "c", "V", "C", "B",
        "F", "O", "J", "H", "😱",
        "💥", "G", "?", "A", "n",
        "💀", "v", "b", "f", "с",
        "K", "т", "😭", "P", "S",
        "W", "e", "t", "м", "T",
        "g", "u", "j", "D", "Q",
        "L", "p", "а", "E", "R",
        ".", "^", "🤯", "", "🎉",
        "h", "🤷", "o", "п", "q",
        "d", "∞", "▥", "r", "Z",
        ":o", "Cc", "?*", "Ah", "Ok",
        "xd", "😺😺", "br", "oh", "c\\",
        "C#", "gn", "no", "Hi", ":/",
        "ok", "??", "BR", "ミク", "A?",
        ":3", ":)", "hi", "Aw", "Or",
        "Hm", "So", ":>", "vg", "No",
        "X", "0", "k", "1", "y",
        "😕", "Д", "😵", "🤔", "xD",
        "ye", ":<", "Aa", "yo", "ah",
        "✓", "hm", "aa", "aw", "mk",
        "F?", "WTF", "bru", "Lol", "Idk",
        "Ccc", "0 B", "OwO", "Nya", "Wat",
        "www", "Who", "...", "Bru", "aaa",
        "thx", "lol", "idc", "vvv", "awa",
        "nvm", "owo", "idk", "Mew", "Ok",
        "nya", ".-.", "uwu", "mew", "にゃ〜",
        "にゃ→", "LOL", "hhh", "Hii", "UwU",
        "._.", "afk", "yep", "ook", "???",
        ";-;", "o.o", "Btw", "ろぼｔ", "Guh",
        "Yep", "EA?", "But", "Hmm", "Vru",
        "Dot", "tps", "Nah", "-w-", ":(",
        "nt", "hr", "gm", ":O", "fr",
        "as", "ер", "x3", "Mi", "Zx",
        "rg", "ad", "av", "kk", "go",
        "rb", "gg", "Gn", "v*", "v",
        "vr", "ed", "+", "5*", "XD",
        "po", "Xd", "65", "12", "C\\",
        "vv", "qwe", "yes", "vff", "Mhh",
        "16*", "okk", "Wtf", "oki", "map",
        "gn", "dot", "にゃ～", "Oh", "fu",
        "1.", "GD", "ai", "sad", "Ugh",
        "Wht", "Tts", "Gun", "Oki", "..",
        "<3", "or", "🥰🥰", "rip", "Alr",
        "Hhh", "Cool", "Test", "back", "bruh",
        "おはよう", "test", "Real", "Boom", "Hmmm",
        "Sigh", "Oops", "Fire", "Who?", "Bruh",
        "Cant", "Meow", "Danf", "Cccc", "Kyuu",
        "0.55", "0.25", "Xool", "Purr", "OwO",
        "list", "why?", "heer", "now?", "????",
        "boom", "meow", "spin", "....", "Okkk",
        "Yeee", "Okey", "Obs*", "purr", "cccc",
        "robo", "Woke", ">.<", "here", "wut?",
        "Dang", "OwO?", "Back", "teto", "ah..",
        "Teto", "Idk", "Wait", "V ok", "UNO!",
        "oops", "Guys", "uno?", "wait", "a...",
        "Also", "Gone", "Welp", "tee?", "Nyaa",
        "And?", "Nya~", "yea?", "it 0", "owo?",
        "Miku", "oop", "??/", "ok", "asd",
        "Owo", "AwA", "Oop", "No?", "wtf",
        ">~<", "VLC", "wat", "Bye", "YAY",
        "NO!", "bye", "in?", "not", "Ok?",
        "Ref", "おはｙ", "てｓｔ", "Abc", "hmm",
        "yea", "Orr", "Red", "Ahh", "bb\\",
        "ok!", "^w^", "@w@", "hai", "Okk",
        "Aaa", "Yay", "Vvv", "lOl", "Yea",
        "Sey", "efr", "alr", "N/A", "nya~",
        "cool", "wat?", ">~<", "well", "nyaa",
        "dang", "???/", "adaw", "BRUH", "stop",
        "aaaa", "now", "Xde", "22%", "Yes",
        "ccc", "-.-", "BR-", "me?", "uhh",
        "0_0", "asas", "hmmm", "yooo", "8 tb",
        "sdf\\", "ミクたん", "TETO", "MIKU", "Wtff",
        ">:3", "zero", "bbb", "Hai", "Huh",
        "no?", "-_-", "you", "XDD", "ye?",
        "lma", "xDD", "vps", "Meow", "WHYYY",
        "agaun", "Aa...", "Bruh*", "Hmmm", "Wdym?",
        "Ohhhh", "Ahaha", "0!!!!", "Miku", "Cool*",
        "+++++", "Logic", "Bruh", "lmfao", "this?",
        "it ok", ":sob:", "おｈしょう", "おはよう*", ".....",
        "|>.<", "Meow?", "owoie", "wdym?", "who g",
        "uh oh", "meow?", "ah ok", "brjuh", "Boom?",
        "Robog", "Ah ok", "Also", "a one", "Hmmmm",
        "Teto?", "Ah...", "What?", "hyrt", "brte",
        "xddd", "logi", "cant", "yeah", "mew?",
        "Why?", "and?", "WATT"
    };

    public PleadService(MatrixClient matrixClient) {
        this.matrixClient = matrixClient;
        this.persistenceFile = Paths.get("plead_enabled.txt");
        loadState();
    }

    private void loadState() {
        if (Files.exists(persistenceFile)) {
            try {
                String content = Files.readString(persistenceFile).trim();
                isEnabled = "true".equalsIgnoreCase(content);
                System.out.println("Loaded plead enabled state: " + isEnabled);
            } catch (IOException e) {
                System.err.println("Error loading plead state: " + e.getMessage());
            }
        }
    }

    private void saveState() {
        try {
            Files.writeString(persistenceFile, String.valueOf(isEnabled));
        } catch (IOException e) {
            System.err.println("Error saving plead state: " + e.getMessage());
        }
    }

    public void togglePlead(String roomId) {
        isEnabled = !isEnabled;
        saveState();
        if (isEnabled) {
            matrixClient.sendText(roomId, "🥺 feature has been enabled.");
        } else {
            matrixClient.sendText(roomId, "🥺 feature has been disabled.");
        }
    }

    public void processMessage(String roomId, String eventId, String body) {
        if (!isEnabled || body == null) {
            return;
        }

        if (body.contains("🥺")) {
            String reaction;
            int chance = random.nextInt(4);
            if (chance == 0) {
                reaction = "🥺";
            } else if (chance == 1) {
                reaction = "👉👈";
            } else if (chance == 2) {
                reaction = "👉😵👈";
            } else {
                reaction = THIRD_CHANCE_REACTIONS[random.nextInt(THIRD_CHANCE_REACTIONS.length)];
            }
            matrixClient.sendReaction(roomId, eventId, reaction);
        }
    }
}
