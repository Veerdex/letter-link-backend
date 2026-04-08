package com.backend.letterlink;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class WordValidationService {

    private WordValidationService() {
    }

    public static ValidationResult validateSubmittedWords(
            List<String> submittedWords,
            WordDictionary dictionary
    ) {
        List<String> validWords = new ArrayList<>();
        List<RejectedWord> rejectedWords = new ArrayList<>();

        if (submittedWords == null || submittedWords.isEmpty()) {
            return new ValidationResult(validWords, rejectedWords);
        }

        // preserve order, remove duplicates
        Set<String> seen = new LinkedHashSet<>();

        for (String rawWord : submittedWords) {
            String word = normalize(rawWord);

            if (word == null) {
                rejectedWords.add(new RejectedWord(rawWord, "invalid_format"));
                continue;
            }

            if (seen.contains(word)) {
                rejectedWords.add(new RejectedWord(word, "duplicate"));
                continue;
            }

            seen.add(word);

            if (!dictionary.contains(word)) {
                rejectedWords.add(new RejectedWord(word, "not_in_dictionary"));
                continue;
            }

            validWords.add(word);
        }

        return new ValidationResult(validWords, rejectedWords);
    }

    private static String normalize(String word) {
        if (word == null) {
            return null;
        }

        word = word.trim().toLowerCase();

        if (word.length() < 3) {
            return null;
        }

        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (c < 'a' || c > 'z') {
                return null;
            }
        }

        return word;
    }

    public static final class ValidationResult {
        public final List<String> validWords;
        public final List<RejectedWord> rejectedWords;

        public ValidationResult(List<String> validWords, List<RejectedWord> rejectedWords) {
            this.validWords = validWords;
            this.rejectedWords = rejectedWords;
        }
    }

    public static final class RejectedWord {
        public final String word;
        public final String reason;

        public RejectedWord(String word, String reason) {
            this.word = word;
            this.reason = reason;
        }
    }
}