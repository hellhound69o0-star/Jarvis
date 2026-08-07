package com.example.selflearn

import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import kotlin.math.exp

/**
 * Lightweight ON-DEVICE self-learning engine, designed for phones with ~1GB RAM.
 *
 * WHY THIS DESIGN (instead of TensorFlow Lite / a neural net):
 *  - No external ML framework needed. TFLite alone can eat 20-50MB+ just sitting idle;
 *    on a 1GB device that's a big chunk of your budget before you've done anything.
 *  - Uses the "hashing trick": every piece of input (a word, a sentence, a tag, a number
 *    written as text, anything) gets converted into a FIXED number of feature buckets by
 *    hashing. Memory usage never grows no matter how much you teach it — it's bounded by
 *    numBuckets * numClasses * 4 bytes, decided up front.
 *  - "Self-learning" here means ONLINE learning: there's no separate training phase.
 *    Every train() call does one real gradient-descent update immediately, and the model
 *    is persisted to disk so it remembers permanently, across app restarts.
 *  - Multi-class classification via one-vs-rest logistic regression with a softmax
 *    normalization at prediction time (this is essentially the same core algorithm
 *    behind tools like Vowpal Wabbit, which was itself built to run in constrained memory).
 *
 * MEMORY MATH (tune numBuckets to fit your device):
 *   numBuckets * 4 bytes * numClasses
 *   e.g. 65536 buckets * 4 bytes * 20 classes ≈ 5 MB total. Very safe on 1GB RAM.
 *   Drop to 1 shl 14 (16384) if you want to support 100+ classes on a very tight device.
 *
 * WHAT COUNTS AS "INPUT":
 *   Anything you can turn into a string. Sentences, single words, comma-separated tags,
 *   even sensor readings if you format them as text (e.g. "accel_x:2.3 accel_y:0.1").
 *   The tokenizer below splits on whitespace/commas AND falls back to character trigrams,
 *   so it still extracts signal from short or non-space-separated input.
 */
class SelfLearningEngine(
    private val storageFile: File,
    private val numBuckets: Int = 1 shl 16, // 65536 — halve this if you need less RAM use
    private val learningRate: Float = 0.15f,
    private val l2: Float = 0.00001f
) {
    // label -> weight vector of size numBuckets (sparse in practice, dense array for speed)
    private val weights = HashMap<String, FloatArray>()
    private val bias = HashMap<String, Float>()
    private val labelCounts = HashMap<String, Int>()

    init { load() }

    // ---------- Feature extraction ----------

    private fun tokenize(input: String): List<String> {
        val lower = input.lowercase().trim()
        val words = lower.split(Regex("\\s+|,|;")).filter { it.isNotBlank() }
        val compact = lower.replace(" ", "")
        val trigrams = mutableListOf<String>()
        if (compact.length >= 3) {
            for (i in 0..compact.length - 3) trigrams.add(compact.substring(i, i + 3))
        }
        return words + trigrams
    }

    private fun featurize(input: String): IntArray {
        val tokens = tokenize(input)
        return IntArray(tokens.size) { i -> Math.floorMod(tokens[i].hashCode(), numBuckets) }
    }

    private fun scoreFor(label: String, features: IntArray): Float {
        val w = weights[label] ?: return 0f
        var s = bias[label] ?: 0f
        for (f in features) s += w[f]
        return s
    }

    private fun ensureLabel(label: String) {
        if (!weights.containsKey(label)) {
            weights[label] = FloatArray(numBuckets)
            bias[label] = 0f
            labelCounts[label] = 0
        }
    }

    // ---------- Public API ----------

    /** Teach the model: this input belongs to this label. Updates the model immediately. */
    @Synchronized
    fun train(input: String, label: String) {
        ensureLabel(label)
        val features = featurize(input)
        val labels = weights.keys.toList()

        val rawScores = labels.associateWith { scoreFor(it, features) }
        val maxScore = rawScores.values.max()
        val expScores = rawScores.mapValues { exp((it.value - maxScore).toDouble()).toFloat() }
        val sumExp = expScores.values.sum().let { if (it == 0f) 1f else it }
        val probs = expScores.mapValues { it.value / sumExp }

        for (l in labels) {
            val target = if (l == label) 1f else 0f
            val error = target - (probs[l] ?: 0f)
            val w = weights[l]!!
            for (f in features) {
                w[f] += learningRate * (error - l2 * w[f])
            }
            bias[l] = (bias[l] ?: 0f) + learningRate * error
        }
        labelCounts[label] = (labelCounts[label] ?: 0) + 1
    }

    /** Predict the most likely label for this input, with a 0..1 confidence score. */
    @Synchronized
    fun predict(input: String): Pair<String, Float>? {
        if (weights.isEmpty()) return null
        val features = featurize(input)
        val rawScores = weights.keys.associateWith { scoreFor(it, features) }
        val maxScore = rawScores.values.maxOrNull() ?: return null
        val expScores = rawScores.mapValues { exp((it.value - maxScore).toDouble()).toFloat() }
        val sumExp = expScores.values.sum().let { if (it == 0f) 1f else it }
        val best = expScores.maxByOrNull { it.value } ?: return null
        return best.key to (best.value / sumExp)
    }

    /** Every label learned so far, with how many times it's been taught. */
    fun knownLabels(): Map<String, Int> = labelCounts.toMap()

    fun forgetLabel(label: String) {
        weights.remove(label); bias.remove(label); labelCounts.remove(label)
    }

    fun resetAll() {
        weights.clear(); bias.clear(); labelCounts.clear()
    }

    // ---------- Persistence ----------

    @Synchronized
    fun save() {
        ObjectOutputStream(storageFile.outputStream()).use { out ->
            out.writeInt(numBuckets)
            out.writeObject(HashMap(weights))
            out.writeObject(HashMap(bias))
            out.writeObject(HashMap(labelCounts))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun load() {
        if (!storageFile.exists()) return
        try {
            ObjectInputStream(storageFile.inputStream()).use { inp ->
                val savedBuckets = inp.readInt()
                if (savedBuckets != numBuckets) return // model dimension mismatch, start fresh
                weights.putAll(inp.readObject() as HashMap<String, FloatArray>)
                bias.putAll(inp.readObject() as HashMap<String, Float>)
                labelCounts.putAll(inp.readObject() as HashMap<String, Int>)
            }
        } catch (e: Exception) {
            // corrupted or incompatible file — start fresh rather than crash
        }
    }
}
