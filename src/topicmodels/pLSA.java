package topicmodels;


/**
 * @author Md. Mustafizur Rahman (mr4xb@virginia.edu)
 * Probabilistic Latent Semantic Analysis Topic Modeling 
 */

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;

import structures.MyPriorityQueue;
import structures._Corpus;
import structures._Doc;
import structures._RankItem;
import structures._SparseFeature;
import utils.Utils;


public class pLSA extends twoTopic {
	// Dirichlet prior for p(\theta|d)
	protected double d_alpha; // smoothing of p(z|d)
	
	protected double[][] topic_term_probabilty ; /* p(w|z) */
	protected double[][] word_topic_sstat; /* fractional count for p(z|d,w) */
	protected double[][] word_topic_prior; /* prior distribution of words under a set of topics, by default it is null */
	
	public pLSA(int number_of_iteration, double converge, double beta, _Corpus c, //arguments for general topic model
			double lambda, double back_ground [], //arguments for 2topic topic model
			int number_of_topics, double alpha) { //arguments for pLSA			
		super(number_of_iteration, converge, beta, c, lambda, back_ground);
		
		this.d_alpha = alpha;
		this.number_of_topics = number_of_topics;
		topic_term_probabilty = new double[this.number_of_topics][this.vocabulary_size];
		word_topic_sstat = new double[this.number_of_topics][this.vocabulary_size];
		word_topic_prior = null;
	}
	
	public void LoadPrior(String vocabulary, String filename, double eta) {		
		if (filename == null || filename.isEmpty())
			return;
		
		try {
			String tmpTxt;
			String[] container;
			
			HashMap<String, Integer> featureNameIndex = new HashMap<String, Integer>();
			BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(vocabulary), "UTF-8"));
			while( (tmpTxt=reader.readLine()) != null ){
				if (tmpTxt.startsWith("#"))
					continue;
				featureNameIndex.put(tmpTxt, featureNameIndex.size());
			}
			reader.close();
			
			int wid, tid = 0, wCount = 0;
			word_topic_prior = new double[number_of_topics][vocabulary_size];
			reader = new BufferedReader(new InputStreamReader(new FileInputStream(filename), "UTF-8"));
			while( (tmpTxt=reader.readLine()) != null ){
				container = tmpTxt.split(" ");
				wCount = 0;
				for(int i=1; i<container.length; i++) {
					if (featureNameIndex.containsKey(container[i])) {
						wid = featureNameIndex.get(container[i]); // map it to a controlled vocabulary term
						word_topic_prior[tid][wid] = eta;
						wCount++;
					}
				}
				
				System.out.println("Prior keywords for Topic " + tid + ": " + wCount);
				tid ++;
			}
			reader.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	@Override
	public String toString() {
		return String.format("pLSA[k:%d, lambda:%.2f]", number_of_topics, m_lambda);
	}

	@Override
	protected void initialize_probability(Collection<_Doc> collection) {	
		// initialize topic document proportion, p(z|d)
		for(_Doc d:collection)
			d.setTopics(number_of_topics, d_alpha-1.0);//allocate memory and randomize it
		
		// initialize term topic matrix p(w|z,\phi)
		for(int i=0;i<number_of_topics;i++)
			Utils.randomize(word_topic_sstat[i], d_beta-1.0);
		imposePrior();
		
		calculate_M_step(0);
	}
	
	protected void imposePrior() {
		if (word_topic_prior!=null) {
			for(int k=0; k<number_of_topics; k++) {
				for(int n=0; n<vocabulary_size; n++)
					word_topic_sstat[k][n] += word_topic_prior[k][n];
			}
		}
	}
	
	@Override
	protected void init() { // clear up for next iteration
		for(int k=0;k<this.number_of_topics;k++)
			Arrays.fill(word_topic_sstat[k], d_beta-1.0);//pseudo counts for p(w|z)
		imposePrior();
		
		//initiate sufficient statistics
		for(_Doc d:m_trainSet)
			Arrays.fill(d.m_sstat, 0);//pseudo counts for p(\theta|d)
	}
	
	@Override
	protected void initTestDoc(_Doc d) {
		//allocate memory and randomize it
		d.setTopics(number_of_topics, d_alpha-1.0);//in real space
	}
	
	@Override
	public double calculate_E_step(_Doc d) {	
		double propB; // background proportion
		double exp; // expectation of each term under topic assignment
		for(_SparseFeature fv:d.getSparse()) {
			int j = fv.getIndex(); // jth word in doc
			double v = fv.getValue();
			
			//-----------------compute posterior----------- 
			double sum = 0;
			for(int k=0;k<this.number_of_topics;k++)
				sum += d.m_topics[k]*topic_term_probabilty[k][j];//shall we compute it in log space?
			
			propB = m_lambda * background_probability[j];
			propB /= propB + (1-m_lambda) * sum;//posterior of background probability
			
			//-----------------compute and accumulate expectations----------- 
			for(int k=0;k<this.number_of_topics;k++) {
				exp = v * (1-propB)*d.m_topics[k]*topic_term_probabilty[k][j]/sum;
				d.m_sstat[k] += exp;
				
				if (m_collectCorpusStats)
					word_topic_sstat[k][j] += exp;
			}
		}
		
		return calculate_log_likelihood(d);
	}
	
	@Override
	public void calculate_M_step(int iter) {
		// update topic-term matrix -------------
		double sum = 0;
		for(int k=0;k<this.number_of_topics;k++) {
			sum = Utils.sumOfArray(word_topic_sstat[k]);
			for(int i=0;i<this.vocabulary_size;i++)
				topic_term_probabilty[k][i] = word_topic_sstat[k][i] / sum;
		}
		
		// update per-document topic distribution vectors
		for(_Doc d:m_trainSet)
			estThetaInDoc(d);
	}
	
	@Override
	protected void estThetaInDoc(_Doc d) {
		double sum = Utils.sumOfArray(d.m_sstat);//estimate the expectation of \theta
		for(int k=0;k<this.number_of_topics;k++)
			d.m_topics[k] = d.m_sstat[k] / sum;
	}
	
	/*likelihod calculation */
	/* M is number of doc
	 * N is number of word in corpus
	 */
	/* p(w,d) = sum_1_M sum_1_N count(d_i, w_j) * log[ lambda*p(w|theta_B) + [lambda * sum_1_k (p(w|z) * p(z|d)) */ 
	//NOTE: cannot be used for unseen documents!
	@Override
	public double calculate_log_likelihood(_Doc d) {
		if (this.m_converge<=0)
			return 1;//no need to compute
		
		double logLikelihood = 0.0, prob;
		for(_SparseFeature fv:d.getSparse()) {
			int j = fv.getIndex();	
			prob = 0.0;
			for(int k=0;k<this.number_of_topics;k++)//\sum_z p(w|z,\theta)p(z|d)
				prob += d.m_topics[k]*topic_term_probabilty[k][j];
			prob = prob*(1-m_lambda) + this.background_probability[j]*m_lambda;//(1-\lambda)p(w|d) * \lambda p(w|theta_b)
			logLikelihood += fv.getValue() * Math.log(prob);
		}
		return logLikelihood;
	}
	
	@Override
	protected double calculate_log_likelihood() {
		if (this.m_converge<=0)
			return 1;//no need to compute
		
		//prior from Dirichlet distributions
		double logLikelihood = 0;
		for(int i=0; i<this.number_of_topics; i++) {
			for(int v=0; v<this.vocabulary_size; v++) {
				logLikelihood += (d_beta-1)*topic_term_probabilty[i][v];
			}
		}
		
		return logLikelihood;
	}
	
	@Override
	public void printTopWords(int k) {
		Arrays.fill(m_sstat, 0);
		for(_Doc d:m_trainSet) {
			for(int i=0; i<number_of_topics; i++)
				m_sstat[i] += d.m_topics[i];
		}
		Utils.L1Normalization(m_sstat);			
		
		for(int i=0; i<topic_term_probabilty.length; i++) {
			MyPriorityQueue<_RankItem> fVector = new MyPriorityQueue<_RankItem>(k);
			for(int j = 0; j < vocabulary_size; j++)
				fVector.add(new _RankItem(m_corpus.getFeature(j), topic_term_probabilty[i][j]));
			System.out.format("Topic %d(%.3f):\t", i, m_sstat[i]);
			for(_RankItem it:fVector)
				System.out.format("%s(%.3f)\t", it.m_name, it.m_value);
			System.out.println();
		}
	}
}
