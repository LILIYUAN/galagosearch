package org.galagosearch.core.scoring;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import org.galagosearch.core.index.DocumentLengthsReader;
import org.galagosearch.core.parse.CorpusReader;
import org.galagosearch.core.parse.Document;
import org.galagosearch.core.parse.TagTokenizer;
import org.galagosearch.core.retrieval.ScoredDocument;
import org.galagosearch.tupleflow.Parameters;

/**
 * Implements the basic unigram Relevance Model.
 * @author irmarc
 */
public class RelevanceModel {

  public static class Gram implements Comparable<Gram> {

    public String term;
    public double score;

    public Gram(String t) {
      term = t;
      score = 0.0;
    }

    // The secondary sort is to have defined behavior for statistically tied samples.
    public int compareTo(Gram that) {
      int result = this.score > that.score ? -1 : (this.score < that.score ? 1 : 0);
      if (result != 0) {
        return result;
      }
      result = (this.term.compareTo(that.term));
      return result;
    }

    public String toString() {
      return "<" + term + "," + score + ">";
    }
  }

  Parameters parameters;
  CorpusReader cReader;
  DocumentLengthsReader docLengths;
  TagTokenizer tokenizer;

  public RelevanceModel(Parameters parameters) {
    this.parameters = parameters;
  }

  /*
   * This should be run while we're waiting for the results.
   *
   */
  public void initialize() throws Exception {
    // Let's make a corpus reader
    String corpusLocation = parameters.get("corpus", null);
    if (corpusLocation == null) { // keep trying
      corpusLocation = parameters.get("index") + File.separator + "corpus";
    }
    cReader = new CorpusReader(corpusLocation);

    // We also need the document lengths
    docLengths = new DocumentLengthsReader(parameters.get("index") + File.separator + "documentLengths");
    tokenizer = new TagTokenizer();
  }

  /*
   * Run this when the Relevance Model is no longer needed.
   */
  public void cleanup() throws Exception {
    cReader.close();
    docLengths.close();
  }

  public ArrayList<Gram> generateGrams(ArrayList<ScoredDocument> initialResults) throws IOException {
    HashMap<Integer, Double> scores = logstoposteriors(initialResults);
    HashMap<String, HashMap<Integer,Integer>> counts = countGrams(initialResults, cReader);
    ArrayList<Gram> scored = scoreGrams(counts, scores, docLengths);
    Collections.sort(scored);
    return scored;
  }


  // Implementation here is identical to the Relevance Model unigram normaliztion in Indri.
  // See RelevanceModel.cpp for details
  protected HashMap<Integer, Double> logstoposteriors(ArrayList<ScoredDocument> results) {
    HashMap<Integer, Double> scores = new HashMap<Integer, Double>();
    if (results.size() == 0) return scores;

    // For normalization
    double K = results.get(0).score;

    // First pass to get the sum
    double sum = 0;
    for (ScoredDocument sd : results) {
      sd.score = Math.exp(K+sd.score);
      sum += sd.score;
    }

    // Normalize
    for (ScoredDocument sd : results) {
      sd.score /= sum;
      scores.put(sd.document, sd.score);
     }
    return scores;
  }

  protected HashMap<String, HashMap<Integer, Integer>> countGrams(ArrayList<ScoredDocument> results, CorpusReader reader) throws IOException {
    HashMap<String, HashMap<Integer, Integer>> counts = new HashMap<String, HashMap<Integer,Integer>>();
    HashMap<Integer, Integer> termCounts;
    Document doc;
    for (ScoredDocument sd : results) {
      doc = reader.getDocument(sd.documentName);
      tokenizer.tokenize(doc);
      for (String term : doc.terms) {
        if (!counts.containsKey(term)) counts.put(term, new HashMap<Integer, Integer>());
        termCounts = counts.get(term);
        if (termCounts.containsKey(sd.document)) termCounts.put(sd.document, termCounts.get(sd.document)+1);
        else termCounts.put(sd.document, 1);
      }
    }
    return counts;
  }

    protected ArrayList<Gram> scoreGrams(HashMap<String, HashMap<Integer, Integer>> counts, 
            HashMap<Integer, Double> scores, DocumentLengthsReader docLengths) throws IOException {
    ArrayList<Gram> grams = new ArrayList<Gram>();
    HashMap<Integer, Integer> termCounts;
    HashMap<Integer, Integer> lengthCache = new HashMap<Integer, Integer>();

    for (String term : counts.keySet()) {
      Gram g = new Gram(term);
      termCounts = counts.get(term);
      for (Integer docID : termCounts.keySet()) {
	  if (!lengthCache.containsKey(docID)) {
	      lengthCache.put(docID, docLengths.getLength(docID));
	  }
	  int length = lengthCache.get(docID);
        g.score += scores.get(docID) * termCounts.get(docID) / length;
      }
      // 1 / fbDocs from the RelevanceModel source code
      g.score *= (1.0 / scores.size());
      grams.add(g);
    }

    return grams;
  }
}
