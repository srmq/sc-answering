package br.ufpe.cin.nlp.sentence;

import org.deeplearning4j.models.word2vec.Word2Vec;
import org.deeplearning4j.text.tokenization.tokenizer.TokenPreProcess;

public class GloveVocabTokenPreprocessor implements TokenPreProcess {
	
	private GloveVocab vocab;
	
	public GloveVocabTokenPreprocessor(GloveVocab vocab) {
		this.vocab = vocab;
	}

	@Override
	public String preProcess(String token) {
		String ret = token;
		if (!vocab.contains(token)) {
			ret = Word2Vec.UNK;
		}
		return ret;
	}

}
