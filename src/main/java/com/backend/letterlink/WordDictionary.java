package com.backend.letterlink;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class WordDictionary {
    private static final int ALPHABET_SIZE = 26;
    private static final int BUCKET_COUNT =
            ALPHABET_SIZE * ALPHABET_SIZE * ALPHABET_SIZE * ALPHABET_SIZE * ALPHABET_SIZE;

    @SuppressWarnings("unchecked")
    private final List<String>[] buckets = new ArrayList[BUCKET_COUNT];

    // For words shorter than 5 letters
    private final List<String> shortWords = new ArrayList<>();

    public WordDictionary() {
    }

    public void addWord(String word) {
        if (word == null) {
            return;
        }

        word = normalize(word);
        if (word == null) {
            return;
        }

        if (word.length() < 5) {
            shortWords.add(word);
            return;
        }

        int index = bucketIndex(word);

        if (buckets[index] == null) {
            buckets[index] = new ArrayList<>();
        }

        buckets[index].add(word);
    }

    public void sortAll() {
        Collections.sort(shortWords);

        for (List<String> bucket : buckets) {
            if (bucket != null) {
                Collections.sort(bucket);
            }
        }
    }

    public boolean contains(String word) {
        if (word == null) {
            return false;
        }

        word = normalize(word);
        if (word == null) {
            return false;
        }

        if (word.length() < 5) {
            return Collections.binarySearch(shortWords, word) >= 0;
        }

        int index = bucketIndex(word);
        List<String> bucket = buckets[index];

        if (bucket == null) {
            return false;
        }

        return Collections.binarySearch(bucket, word) >= 0;
    }

    public List<String> getBucket(String word) {
        if (word == null) {
            return List.of();
        }

        word = normalize(word);
        if (word == null || word.length() < 5) {
            return List.of();
        }

        int index = bucketIndex(word);
        List<String> bucket = buckets[index];
        return bucket == null ? List.of() : bucket;
    }

    public int bucketSize(String word) {
        List<String> bucket = getBucket(word);
        return bucket.size();
    }

    public int totalBucketCount() {
        return BUCKET_COUNT;
    }

    public int usedBucketCount() {
        int count = 0;
        for (List<String> bucket : buckets) {
            if (bucket != null && !bucket.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    public int shortWordCount() {
        return shortWords.size();
    }

    public static WordDictionary loadFromFile(Path path) throws IOException {
        WordDictionary dictionary = new WordDictionary();

        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                dictionary.addWord(line);
            }
        }

        dictionary.sortAll();
        return dictionary;
    }

    public static WordDictionary loadFromResource(String resourcePath) throws IOException {
        InputStream input = WordDictionary.class.getClassLoader().getResourceAsStream(resourcePath);

        if (input == null) {
            throw new IOException("Could not find resource: " + resourcePath);
        }

        WordDictionary dictionary = new WordDictionary();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input))) {
            String line;
            while ((line = reader.readLine()) != null) {
                dictionary.addWord(line);
            }
        }

        dictionary.sortAll();
        return dictionary;
    }

    private static String normalize(String word) {
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

    private static int bucketIndex(String word) {
        return index5(
                word.charAt(0),
                word.charAt(1),
                word.charAt(2),
                word.charAt(3),
                word.charAt(4)
        );
    }

    private static int index5(char c1, char c2, char c3, char c4, char c5) {
        return (((((toIndex(c1) * ALPHABET_SIZE)
                + toIndex(c2)) * ALPHABET_SIZE
                + toIndex(c3)) * ALPHABET_SIZE
                + toIndex(c4)) * ALPHABET_SIZE
                + toIndex(c5));
    }

    private static int toIndex(char c) {
        return c - 'a';
    }
}