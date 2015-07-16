/**
 * 
 */
package topicmodels.attributeaware;

import java.io.IOException;
import java.text.ParseException;
import java.util.Arrays;
import java.util.Collection;

import optimization.gradientBasedMethods.ProjectedGradientDescent;
import optimization.gradientBasedMethods.stats.OptimizerStats;
import optimization.linesearch.ArmijoLineSearchMinimizationAlongProjectionArc;
import optimization.linesearch.InterpolationPickFirstStep;
import optimization.linesearch.LineSearchMethod;
import optimization.stopCriteria.CompositeStopingCriteria;
import optimization.stopCriteria.ProjectedGradientL2Norm;
import structures._Corpus;
import structures._Doc;
import structures._SparseFeature;
import topicmodels.multithreads.LDA_Variational_multithread;
import topicmodels.posteriorRegularization.PairwiseAttributeConstraints;
import utils.Utils;
import Analyzer.newEggAnalyzer;

/**
 * @author hongning
 * Attribute aware LDA - using posterior regularization to incorporate attribute for topic modeling
 */
public class AttributeAwareLDA_VarMultiThread extends LDA_Variational_multithread {
	
	public class AttributeAwareLDA_worker extends LDA_worker {	
		double[] m_tAssignments; // accumulated topic assignments across words 
		PairwiseAttributeConstraints m_constraint;
		CompositeStopingCriteria m_compositeStop;
		LineSearchMethod m_ls;
		ProjectedGradientDescent m_optimizer;
		int m_success, m_total, m_round;
		
		public AttributeAwareLDA_worker() {
			super();
			m_tAssignments = new double[number_of_topics];
			
			double gdelta = 1e-5, istp = 1.0;
			
			m_compositeStop = new CompositeStopingCriteria();
			m_compositeStop.add(new ProjectedGradientL2Norm(gdelta));
			m_ls = new ArmijoLineSearchMinimizationAlongProjectionArc(new InterpolationPickFirstStep(istp));
			m_optimizer = new ProjectedGradientDescent(m_ls);
			m_optimizer.setMaxIterations(50);
			
			m_constraint = new PairwiseAttributeConstraints(number_of_topics);
			m_constraint.setDebugLevel(-1);
			
			m_round = 0;
		}
		
		@Override
		public void run() {
			m_likelihood = 0;
			m_total = 0;
			m_success = 0;
			
			for(_Doc d:m_corpus) {
				if (d.hasSegments())
					m_likelihood += calculate_E_step_withSegments(d);
				else if (m_round>20) // only after we have a reasonable model
					m_likelihood += calculate_E_step(d);
			}
			
			
			if (m_round>20)
				System.out.format("%.3f\n", (double)m_success/m_total);
			m_round ++;
		}
		
		void initEstPhi(_Doc d) {
			int wid;
			double v, logSum;
			_SparseFeature fv[] = d.getSparse(), spFea;
			for(int n=0; n<fv.length; n++) {
				//allocate the words by attribute and topic combination
				spFea = fv[n];
				wid = spFea.getIndex();
				v = spFea.getValue();												
				
				for(int i=0; i<number_of_topics; i++) 
					d.m_phi[n][i] = v*topic_term_probabilty[i][wid] + Utils.digamma(0.1);
				
				logSum = Utils.logSumOfExponentials(d.m_phi[n]);
				for(int i=0; i<number_of_topics; i++)
					d.m_phi[n][i] = Math.exp(d.m_phi[n][i] - logSum);
			}
		}
		
		public double calculate_E_step_withSegments(_Doc d) {
			double last = m_varConverge>0?calculate_log_likelihood(d):1, current = last, converge, logSum, v;
			int iter = 0, wid;
			double[] values;
			_SparseFeature fv[] = d.getSparse(), spFea;
			
			do {
				//variational inference for p(z|w,\phi)
				for(int n=0; n<fv.length; n++) {
					//allocate the words by attribute and topic combination
					spFea = fv[n];
					wid = spFea.getIndex();
					values = spFea.getValues();
					
					//reset the estimates
					Arrays.fill(d.m_phi[n], -100);//exp(-100) should be small enough
					for(int a=0; a<values.length; a++) {
						v = values[a];
						if (v<1)//no observations (at least 1.0)
							continue;
						else if (a<m_attributeSize) {
							for(int i=0; i<number_of_topics; i++) {
								//special organization of topics
								if (i%m_attributeSize==a)//disable the proportion from the other attributes
									d.m_phi[n][i] = v*topic_term_probabilty[i][wid] + Utils.digamma(d.m_sstat[i]);
							}
						} else {//mixing part of all possible attributes
							for(int i=0; i<number_of_topics; i++)
								d.m_phi[n][i] = Utils.logSum(d.m_phi[n][i], v*topic_term_probabilty[i][wid] + Utils.digamma(d.m_sstat[i]));
						}
					}
					
					logSum = Utils.logSumOfExponentials(d.m_phi[n]);
					for(int i=0; i<number_of_topics; i++)
						d.m_phi[n][i] = Math.exp(d.m_phi[n][i] - logSum);
				}
				
				//variational inference for p(\theta|\gamma)
				System.arraycopy(m_alpha, 0, d.m_sstat, 0, number_of_topics);
				for(int n=0; n<fv.length; n++) {
					v = fv[n].getValue();
					for(int i=0; i<number_of_topics; i++) 
						d.m_sstat[i] += d.m_phi[n][i] * v;// expectation of word assignment to topic
				}				
				
				if (m_varConverge>0) {
					current = calculate_log_likelihood(d);			
					converge = Math.abs((current - last)/last);
					last = current;
					
					if (converge<m_varConverge)
						break;
				}
			} while(++iter<m_varMaxIter);
			
			//collect the sufficient statistics after convergence
			this.collectStats(d);
			
			return current;
		}
		
		@Override
		public double calculate_E_step(_Doc d) {	
			double last = m_varConverge>0?calculate_log_likelihood(d):1, current = last, converge, logSum, v;
			int iter = 0, wid;
			_SparseFeature fv[] = d.getSparse(), spFea;
			
			//Step 0: variational inference for p(\theta|\gamma)
			initEstPhi(d);
			
			//we need to collect the expectations for posterior regularization construction
			Arrays.fill(m_tAssignments, 0);
			for(int n=0; n<fv.length; n++) {
				v = fv[n].getValue();
				for(int i=0; i<number_of_topics; i++) 
					m_tAssignments[i] += d.m_phi[n][i] * v;// expectation of word assignment to topic
			}
			
			for(int k=0; k<number_of_topics; k++)
				d.m_sstat[k] = m_alpha[k] + m_tAssignments[k];
			
			do {
				//variational inference for p(z|w,\phi)
				for(int n=0; n<fv.length; n++) {
					//allocate the words by attribute and topic combination
					spFea = fv[n];
					wid = spFea.getIndex();
					v = spFea.getValue();												
					
					for(int i=0; i<number_of_topics; i++) {
						//first remove self from the accumulated expectation
						m_tAssignments[i] -= d.m_phi[n][i] * v;
					
						//then compute the unregularized posterior
						d.m_phi[n][i] = v*topic_term_probabilty[i][wid] + Utils.digamma(d.m_sstat[i]);
					}
					
					logSum = Utils.logSumOfExponentials(d.m_phi[n]);
					for(int i=0; i<number_of_topics; i++)
						d.m_phi[n][i] = Math.exp(d.m_phi[n][i] - logSum);
					
					// then we are regularizing the PR here
					m_constraint.reset(d.m_phi[n], m_tAssignments);
					m_optimizer.reset();
					
					if (m_optimizer.optimize(m_constraint, new OptimizerStats(), m_compositeStop)) {
						d.m_phi[n] = m_constraint.getPosterior(); // get the regularized posterior here
						m_success ++;
					}
					m_total++;
										
					//re-accumulate the expectation of topic assignments					
					for(int i=0; i<number_of_topics; i++) 
						m_tAssignments[i] += d.m_phi[n][i] * v;
				}
				
				//variational inference for p(\theta|\gamma)
				Arrays.fill(m_tAssignments, 0);
				for(int n=0; n<fv.length; n++) {
					v = fv[n].getValue();
					for(int i=0; i<number_of_topics; i++) 
						m_tAssignments[i] += d.m_phi[n][i] * v;// expectation of word assignment to topic
				}
				
				for(int k=0; k<number_of_topics; k++)
					d.m_sstat[k] = m_alpha[k] + m_tAssignments[k];
				
				if (m_varConverge>0) {
					current = calculate_log_likelihood(d);			
					converge = Math.abs((current - last)/last);
					last = current;
					
					if (converge<m_varConverge)
						break;
				}
			} while(++iter<m_varMaxIter);
			
			//collect the sufficient statistics after convergence
			this.collectStats(d);
			
			return current;
		}
	}
	
	int m_attributeSize;//[0, m_attributeSize] sections in _SparseFeature have attribute-based constraints
	
	public AttributeAwareLDA_VarMultiThread(int number_of_iteration,
			double converge, double beta, _Corpus c, double lambda,
			int number_of_topics, double alpha,
			int varMaxIter, double varConverge, int attributeSize) {
		super(number_of_iteration, converge, beta, c, lambda,
				number_of_topics*attributeSize, alpha, varMaxIter, varConverge);
		
		m_attributeSize = attributeSize;
	}
	
	@Override
	protected void imposePrior() {
		if (word_topic_prior!=null) {
			int priorSize = word_topic_prior.length;
			if (number_of_topics<=priorSize*2) {
				System.err.println("Topic size has to be at least twice as seed aspects!");
				System.exit(-1);
			}
			
			int tid = 0;
			for(int k=0; k<priorSize; k++) {
				for(int n=0; n<vocabulary_size; n++) {
					word_topic_sstat[tid][n] += word_topic_prior[k][n];
					word_topic_sstat[tid+1][n] += word_topic_prior[k][n];
				}
				tid += 2;
			}
		}
	}
	
	@Override
	protected void initialize_probability(Collection<_Doc> collection) {
		int cores = Runtime.getRuntime().availableProcessors();
//		int cores = 1;//debugging code
		m_threadpool = new Thread[cores];
		m_workers = new AttributeAwareLDA_worker[cores];
		
		for(int i=0; i<cores; i++)
			m_workers[i] = new AttributeAwareLDA_worker();
		
		int workerID = 0;
		for(_Doc d:collection) {
			m_workers[workerID%cores].addDoc(d);
			workerID++;
		}
		
		// initialize with all smoothing terms
		init();
		
		// initialize topic-word allocation, p(w|z)
		for(_Doc d:collection) {
			d.setTopics4Variational(number_of_topics, d_alpha);//allocate memory and randomize it
			collectStats(d);
		}
		
		calculate_M_step(0);
	}

	public static void main(String[] args) throws IOException, ParseException {	
		int classNumber = 5; //Define the number of classes in this Naive Bayes.
		int Ngram = 2; //The default value is unigram. 
		int lengthThreshold = 5; //Document length threshold
		
		int number_of_topics = 16;
		double alpha = 1.0 + 1e-2, beta = 1.0 + 1e-3, eta = 5.0;//these two parameters must be larger than 1!!!
		double converge = -1, lambda = 0.7; // negative converge means do need to check likelihood convergency
		int topK = 20, number_of_iteration = 100;
		int attributeSize = 2;//pro and con in NewEgg
		
		/*****The parameters used in loading files.*****/
		String pCategory = "camera"; // camera
		
		String newEggFolder = "./data/NewEgg";
		String amazonFolder = String.format("./data/amazon/%s/topicmodel", pCategory);
		String suffix = ".json";
		String tokenModel = "./data/Model/en-token.bin"; //Token model.
		String aspectlist = String.format("./data/Model/aspect_%s.txt", pCategory);
		String fvFile = String.format("./data/Features/fv_%dgram_topicmodel_%s.txt", Ngram, pCategory);

//		/*****Parameters in feature selection.*****/
//		String stopwords = "./data/Model/stopwords.dat";
//		String featureSelection = "DF"; //Feature selection method.
//		double startProb = 0.5; // Used in feature selection, the starting point of the features.
//		double endProb = 0.999; // Used in feature selection, the ending point of the features.
//		int DFthreshold = 10; // Filter the features with DFs smaller than this threshold.
//		
//		System.out.println("Performing feature selection, wait...");
//		newEggAnalyzer analyzer = new newEggAnalyzer(tokenModel, classNumber, null, Ngram, lengthThreshold, pCategory);
//		analyzer.LoadStopwords(stopwords);
//		analyzer.LoadNewEggDirectory(newEggFolder, suffix); // load NewEgg reviews
//		analyzer.LoadDirectory(amazonFolder, suffix); // load amazon reviews
//		analyzer.featureSelection(fvFile, featureSelection, startProb, endProb, DFthreshold); //Select the features.
		
		System.out.println("Creating feature vectors, wait...");
		newEggAnalyzer analyzer = new newEggAnalyzer(tokenModel, classNumber, fvFile, Ngram, lengthThreshold, pCategory);
		analyzer.LoadNewEggDirectory(newEggFolder, suffix); // load NewEgg reviews
		analyzer.LoadDirectory(amazonFolder, suffix); // load amazon reviews
		
		_Corpus c = analyzer.returnCorpus(null); // Get the collection of all the documents.
		
		AttributeAwareLDA_VarMultiThread model 
			= new AttributeAwareLDA_VarMultiThread(number_of_iteration, converge, beta, c, 
				lambda, number_of_topics, alpha, 10, -1, attributeSize);
		
		model.LoadPrior(aspectlist, eta);
		model.setDisplay(true);
		model.EMonCorpus();
		model.printTopWords(topK);
	}
}

