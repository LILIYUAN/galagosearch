// BSD License (http://www.galagosearch.org/license)

package org.galagosearch.core.parse;

import org.galagosearch.tupleflow.IncompatibleProcessorException;
import org.galagosearch.tupleflow.InputClass;
import org.galagosearch.tupleflow.Linkage;
import org.galagosearch.tupleflow.NullProcessor;
import org.galagosearch.tupleflow.OutputClass;
import org.galagosearch.tupleflow.Processor;
import org.galagosearch.tupleflow.Source;
import org.galagosearch.tupleflow.Step;
import org.galagosearch.tupleflow.TupleFlowParameters;
import org.galagosearch.tupleflow.execution.Verified;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import org.tartarus.snowball.ext.englishStemmer;

/**
 *
 * @author trevor
 * sjh: modified to accept numbered documents as required.
 */
@Verified
public class Porter2Stemmer implements Processor<Document>, Source<Document> {
  englishStemmer stemmer = new englishStemmer();
  HashMap<String, String> cache = new HashMap();
  public Processor<Document> processor = new NullProcessor(Document.class);

  public void process(Document document) throws IOException {
    List<String> words = document.terms;

    for (int i = 0; i < words.size(); i++) {
      String word = words.get(i);

      if (word != null) {
        if (cache.containsKey(word)) {
          words.set(i, cache.get(word));
        } else {
          stemmer.setCurrent(word);
          if (stemmer.stem()) {
            String stem = stemmer.getCurrent();
            words.set(i, stem);
            cache.put(word, stem);
          } else {
            cache.put(word, word);
          }
        }

        if (cache.size() > 50000) {
          cache.clear();
        }
      }
    }
    if(document instanceof NumberedDocument){
      processor.process( (NumberedDocument) document);
    } else {
      processor.process(document);
    }
  }

  public void setProcessor(Step processor) throws IncompatibleProcessorException {
    Linkage.link(this, processor);
  }
  public static String getInputClass(TupleFlowParameters params){
    return params.getXML().get("class", "org.galagosearch.core.parse.Document");
  }
  public static String getOutputClass(TupleFlowParameters params){
    return params.getXML().get("class", "org.galagosearch.core.parse.Document");
  }
  public static String[] getOutputOrder(TupleFlowParameters parameters) {
    return new String[0];
  }
  public void close() throws IOException {
    processor.close();
  }
}
