package mltk.predictor.gam;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

import mltk.cmdline.Argument;
import mltk.cmdline.CmdLineParser;
import mltk.core.Attribute;
import mltk.core.BinnedAttribute;
import mltk.core.Instance;
import mltk.core.Instances;
import mltk.core.NominalAttribute;
import mltk.core.Attribute.Type;
import mltk.core.io.InstancesReader;
import mltk.predictor.BaggedEnsemble;
import mltk.predictor.BaggedEnsembleLearner;
import mltk.predictor.Bagging;
import mltk.predictor.BoostedEnsemble;
import mltk.predictor.Learner;
import mltk.predictor.Regressor;
import mltk.predictor.function.Array2D;
import mltk.predictor.function.CompressionUtils;
import mltk.predictor.function.Function2D;
import mltk.predictor.function.SquareCutter;
import mltk.predictor.io.PredictorReader;
import mltk.predictor.io.PredictorWriter;
import mltk.util.OptimUtils;
import mltk.util.Random;
import mltk.util.StatUtils;
import mltk.util.tuple.IntPair;

/**
 * Class for learning GA^2M models via gradient boosting.
 * 
 * <p>
 * Reference:<br>
 * Y. Lou, R. Caruana, J. Gehrke, and G. Hooker. Accurate intelligible models with pairwise interactions. In
 * <i>Proceedings of the 19th ACM SIGKDD International Conference on Knowledge Discovery and Data Mining (KDD)</i>,
 * Chicago, IL, USA, 2013.
 * </p>
 * 
 * @author Yin Lou
 * 
 */
public class GA2MLearner extends Learner {

	private boolean verbose;
	private int baggingIters;
	private int maxNumIters;
	private Task task;
	private double learningRate;
	private GAM gam;
	private List<IntPair> pairs;
	private Instances validSet;

	/**
	 * Constructor.
	 */
	public GA2MLearner() {
		verbose = false;
		baggingIters = 100;
		maxNumIters = -1;
		learningRate = 0.01;
		task = Task.REGRESSION;
	}

	/**
	 * Returns <code>true</code> if we output something during the training.
	 * 
	 * @return <code>true</code> if we output something during the training.
	 */
	public boolean isVerbose() {
		return verbose;
	}

	/**
	 * Sets whether we output something during the training.
	 * 
	 * @param verbose the switch if we output things during training.
	 */
	public void setVerbose(boolean verbose) {
		this.verbose = verbose;
	}

	/**
	 * Returns the number of bagging iterations.
	 * 
	 * @return the number of bagging iterations.
	 */
	public int getBaggingIters() {
		return baggingIters;
	}

	/**
	 * Sets the number of bagging iterations.
	 * 
	 * @param baggingIters the number of bagging iterations.
	 */
	public void setBaggingIters(int baggingIters) {
		this.baggingIters = baggingIters;
	}

	/**
	 * Returns the maximum number of iterations.
	 * 
	 * @return the maximum number of iterations.
	 */
	public int getMaxNumIters() {
		return maxNumIters;
	}

	/**
	 * Sets the maximum number of iterations.
	 * 
	 * @param maxNumIters the maximum number of iterations.
	 */
	public void setMaxNumIters(int maxNumIters) {
		this.maxNumIters = maxNumIters;
	}

	/**
	 * Returns the learning rate.
	 * 
	 * @return the learning rate.
	 */
	public double getLearningRate() {
		return learningRate;
	}

	/**
	 * Sets the learning rate.
	 * 
	 * @param learningRate the learning rate.
	 */
	public void setLearningRate(double learningRate) {
		this.learningRate = learningRate;
	}

	/**
	 * Returns the task of this learner.
	 * 
	 * @return the task of this learner.
	 */
	public Task getTask() {
		return task;
	}

	/**
	 * Sets the task of this learner.
	 * 
	 * @param task the new task.
	 */
	public void setTask(Task task) {
		this.task = task;
	}

	/**
	 * Returns the GAM.
	 * 
	 * @return the GAM.
	 */
	public GAM getGAM() {
		return gam;
	}

	/**
	 * Sets the GAM.
	 * 
	 * @param gam the GAM.
	 */
	public void setGAM(GAM gam) {
		this.gam = gam;
	}

	/**
	 * Returns the list of feature interaction pairs.
	 * 
	 * @return the list of feature interaction pairs.
	 */
	public List<IntPair> getPairs() {
		return pairs;
	}

	/**
	 * Sets the list of feature interaction pairs.
	 * 
	 * @param pairs the list of feature interaction pairs.
	 */
	public void setPairs(List<IntPair> pairs) {
		this.pairs = pairs;
	}

	/**
	 * Returns the validation set.
	 * 
	 * @return the validation set.
	 */
	public Instances getValidSet() {
		return validSet;
	}

	/**
	 * Sets the validation set.
	 * 
	 * @param validSet the validation set.
	 */
	public void setValidSet(Instances validSet) {
		this.validSet = validSet;
	}

	/**
	 * Builds a classifier.
	 * 
	 * @param gam the GAM.
	 * @param terms the list of feature interaction pairs.
	 * @param trainSet the training set.
	 * @param validSet the validation set.
	 * @param maxNumIters the maximum number of iterations.
	 */
	public void buildClassifier(GAM gam, List<IntPair> terms, Instances trainSet, Instances validSet, int maxNumIters) {
		List<BoostedEnsemble> regressors = new ArrayList<>(terms.size());
		int[] indices = new int[terms.size()];
		for (int i = 0; i < indices.length; i++) {
			indices[i] = gam.terms.indexOf(terms.get(i));
			regressors.add(new BoostedEnsemble());
		}

		// Backup targets
		int[] target = new int[trainSet.size()];
		for (int i = 0; i < target.length; i++) {
			target[i] = (int) trainSet.get(i).getTarget();
		}

		// Create bags
		Instances[] bags = Bagging.createBags(trainSet, baggingIters);
		SquareCutter cutter = new SquareCutter(true);
		BaggedEnsembleLearner learner = new BaggedEnsembleLearner(bags.length, cutter);

		// Initialize predictions
		double[] predictionTrain = new double[trainSet.size()];
		double[] predictionValid = new double[validSet.size()];

		for (int i = 0; i < predictionTrain.length; i++) {
			Instance instance = trainSet.get(i);
			predictionTrain[i] = gam.regress(instance);
		}
		for (int i = 0; i < predictionValid.length; i++) {
			Instance instance = validSet.get(i);
			predictionValid[i] = gam.regress(instance);
		}

		List<Double> errorList = new ArrayList<>(maxNumIters);

		// Gradient boosting
		for (int iter = 0; iter < maxNumIters; iter++) {
			int k = iter % terms.size();
			// Derivitive to attribute k
			// Minimizes the loss function: log(1 + exp(-yF))
			for (int i = 0; i < trainSet.size(); i++) {
				double r = OptimUtils.getPseudoResidual(predictionTrain[i], target[i]);
				trainSet.get(i).setTarget(r);
			}

			BoostedEnsemble boostedEnsemble = regressors.get(k);

			// Train model
			IntPair term = terms.get(k);
			cutter.setAttIndices(term.v1, term.v2);
			BaggedEnsemble baggedEnsemble = learner.build(bags);
			if (learningRate != 1) {
				for (int i = 0; i < baggedEnsemble.size(); i++) {
					Function2D func = (Function2D) baggedEnsemble.get(i);
					func.multiply(learningRate);
				}
			}
			boostedEnsemble.add(baggedEnsemble);

			// Update predictions
			for (int i = 0; i < trainSet.size(); i++) {
				Instance instance = trainSet.get(i);
				double pred = baggedEnsemble.regress(instance);
				predictionTrain[i] += pred;
			}
			for (int i = 0; i < validSet.size(); i++) {
				Instance instance = validSet.get(i);
				double pred = baggedEnsemble.regress(instance);
				predictionValid[i] += pred;
			}

			double error = 0.0;
			for (int i = 0; i < validSet.size(); i++) {
				int cls = (int) validSet.get(i).getTarget();
				int pred = predictionValid[i] >= 0 ? 1 : 0;
				if (pred != cls) {
					error++;
				}
			}
			error /= validSet.size();
			errorList.add(error);
			if (verbose) {
				System.out.println("Iteration " + iter + " term " + k + ": " + error);
			}
		}

		// Search the best model on validation set
		double min = Double.POSITIVE_INFINITY;
		int idx = -1;
		for (int i = 0; i < errorList.size(); i++) {
			if (errorList.get(i) < min) {
				min = errorList.get(i);
				idx = i;
			}
		}

		// Remove trees
		int n = idx / terms.size();
		int m = idx % terms.size();
		for (int k = 0; k < terms.size(); k++) {
			BoostedEnsemble boostedEnsemble = regressors.get(k);
			for (int i = boostedEnsemble.size(); i > n + 1; i--) {
				boostedEnsemble.removeLast();
			}
			if (k > m) {
				boostedEnsemble.removeLast();
			}
		}

		// Restore targets
		for (int i = 0; i < target.length; i++) {
			trainSet.get(i).setTarget(target[i]);
		}

		// Compress model
		List<Attribute> attributes = trainSet.getAttributes();
		for (int i = 0; i < regressors.size(); i++) {
			BoostedEnsemble boostedEnsemble = regressors.get(i);
			IntPair term = terms.get(i);
			Function2D function = CompressionUtils.compress(term.v1, term.v2, boostedEnsemble);
			Attribute f1 = attributes.get(term.v1);
			Attribute f2 = attributes.get(term.v2);
			int n1 = -1;
			if (f1.getType() == Type.BINNED) {
				n1 = ((BinnedAttribute) f1).getNumBins();
			} else if (f1.getType() == Type.NOMINAL) {
				n1 = ((NominalAttribute) f1).getCardinality();
			}
			int n2 = -1;
			if (f2.getType() == Type.BINNED) {
				n2 = ((BinnedAttribute) f2).getNumBins();
			} else if (f1.getType() == Type.NOMINAL) {
				n2 = ((NominalAttribute) f2).getCardinality();
			}
			Regressor newRegressor = function;
			if (n1 > 0 && n2 > 0) {
				newRegressor = CompressionUtils.convert(n1, n2, function);
			}
			if (indices[i] < 0) {
				gam.add(new int[] { term.v1, term.v2 }, newRegressor);
			} else {
				Regressor regressor = gam.regressors.get(indices[i]);
				if (regressor instanceof Function2D) {
					Function2D func = (Function2D) regressor;
					func.add(function);
				} else if (regressor instanceof Array2D) {
					Array2D ary = (Array2D) regressor;
					ary.add((Array2D) newRegressor);
				} else {
					throw new RuntimeException("Failed to add new regressor");
				}
			}
		}
	}

	/**
	 * Builds a classifier.
	 * 
	 * @param gam the GAM.
	 * @param terms the list of feature interaction pairs.
	 * @param trainSet the training set.
	 * @param maxNumIters the maximum number of iterations.
	 */
	public void buildClassifier(GAM gam, List<IntPair> terms, Instances trainSet, int maxNumIters) {
		List<BoostedEnsemble> regressors = new ArrayList<>();
		int[] indices = new int[terms.size()];
		for (int i = 0; i < indices.length; i++) {
			indices[i] = gam.terms.indexOf(terms.get(i));
			regressors.add(new BoostedEnsemble());
		}

		// Backup targets
		int[] target = new int[trainSet.size()];
		for (int i = 0; i < target.length; i++) {
			target[i] = (int) trainSet.get(i).getTarget();
		}

		// Create bags
		Instances[] bags = Bagging.createBags(trainSet, baggingIters);
		SquareCutter cutter = new SquareCutter(true);
		BaggedEnsembleLearner learner = new BaggedEnsembleLearner(bags.length, cutter);

		// Initialize predictions
		double[] predictionTrain = new double[trainSet.size()];

		for (int i = 0; i < predictionTrain.length; i++) {
			Instance instance = trainSet.get(i);
			predictionTrain[i] = gam.regress(instance);
		}

		List<Double> errorList = new ArrayList<>(maxNumIters);

		// Gradient boosting
		for (int iter = 0; iter < maxNumIters; iter++) {
			int k = iter % terms.size();
			// Derivitive to attribute k
			// Minimizes the loss function: log(1 + exp(-2yF))
			for (int i = 0; i < trainSet.size(); i++) {
				double r = OptimUtils.getPseudoResidual(predictionTrain[i], target[i]);
				trainSet.get(i).setTarget(r);
			}

			BoostedEnsemble boostedEnsemble = regressors.get(k);

			// Train model
			IntPair term = terms.get(k);
			cutter.setAttIndices(term.v1, term.v2);
			BaggedEnsemble baggedEnsemble = learner.build(bags);
			if (learningRate != 1) {
				for (int i = 0; i < baggedEnsemble.size(); i++) {
					Function2D func = (Function2D) baggedEnsemble.get(i);
					func.multiply(learningRate);
				}
			}
			boostedEnsemble.add(baggedEnsemble);

			// Update predictions
			for (int i = 0; i < trainSet.size(); i++) {
				Instance instance = trainSet.get(i);
				double pred = baggedEnsemble.regress(instance);
				predictionTrain[i] += pred;
			}

			double error = 0.0;
			for (int i = 0; i < target.length; i++) {
				int cls = (int) target[i];
				int pred = predictionTrain[i] >= 0 ? 1 : 0;
				if (pred != cls) {
					error++;
				}
			}
			error /= trainSet.size();
			errorList.add(error);
			if (verbose) {
				System.out.println("Iteration " + iter + " term " + k + ": " + error);
			}
		}

		// Restore targets
		for (int i = 0; i < target.length; i++) {
			trainSet.get(i).setTarget(target[i]);
		}

		// Compress model
		List<Attribute> attributes = trainSet.getAttributes();
		for (int i = 0; i < regressors.size(); i++) {
			BoostedEnsemble boostedEnsemble = regressors.get(i);
			IntPair term = terms.get(i);
			Function2D function = CompressionUtils.compress(term.v1, term.v2, boostedEnsemble);
			Attribute f1 = attributes.get(term.v1);
			Attribute f2 = attributes.get(term.v2);
			int n1 = -1;
			if (f1.getType() == Type.BINNED) {
				n1 = ((BinnedAttribute) f1).getNumBins();
			} else if (f1.getType() == Type.NOMINAL) {
				n1 = ((NominalAttribute) f1).getCardinality();
			}
			int n2 = -1;
			if (f2.getType() == Type.BINNED) {
				n2 = ((BinnedAttribute) f2).getNumBins();
			} else if (f1.getType() == Type.NOMINAL) {
				n2 = ((NominalAttribute) f2).getCardinality();
			}
			Regressor newRegressor = function;
			if (n1 > 0 && n2 > 0) {
				newRegressor = CompressionUtils.convert(n1, n2, function);
			}
			if (indices[i] < 0) {
				gam.add(new int[] { term.v1, term.v2 }, newRegressor);
			} else {
				Regressor regressor = gam.regressors.get(indices[i]);
				if (regressor instanceof Function2D) {
					Function2D func = (Function2D) regressor;
					func.add(function);
				} else if (regressor instanceof Array2D) {
					Array2D ary = (Array2D) regressor;
					ary.add((Array2D) newRegressor);
				} else {
					throw new RuntimeException("Failed to add new regressor");
				}
			}
		}
	}

	/**
	 * Builds a regressor.
	 * 
	 * @param gam the GAM.
	 * @param terms the list of feature interaction pairs.
	 * @param trainSet the training set.
	 * @param validSet the validation set.
	 * @param maxNumIters the maximum number of iterations.
	 */
	public void buildRegressor(GAM gam, List<IntPair> terms, Instances trainSet, Instances validSet, int maxNumIters) {
		List<BoostedEnsemble> regressors = new ArrayList<>();
		int[] indices = new int[terms.size()];
		for (int i = 0; i < indices.length; i++) {
			indices[i] = gam.terms.indexOf(terms.get(i));
			regressors.add(new BoostedEnsemble());
		}

		// Backup targets
		double[] target = new double[trainSet.size()];
		for (int i = 0; i < target.length; i++) {
			target[i] = trainSet.get(i).getTarget();
		}

		// Create bags
		Instances[] bags = Bagging.createBags(trainSet, baggingIters);

		SquareCutter cutter = new SquareCutter();
		BaggedEnsembleLearner learner = new BaggedEnsembleLearner(baggingIters, cutter);

		// Initialize residuals
		double[] residualTrain = new double[trainSet.size()];
		double[] residualValid = new double[validSet.size()];
		for (int i = 0; i < trainSet.size(); i++) {
			Instance instance = trainSet.get(i);
			residualTrain[i] = instance.getTarget() - gam.regress(instance);
		}
		for (int i = 0; i < validSet.size(); i++) {
			Instance instance = validSet.get(i);
			residualValid[i] = instance.getTarget() - gam.regress(instance);
		}

		List<Double> rmseList = new ArrayList<>(maxNumIters);

		// Gradient boosting
		for (int iter = 0; iter < maxNumIters; iter++) {
			int k = iter % terms.size();
			// Derivative to attribute k
			// Equivalent to residual
			BoostedEnsemble boostedEnsemble = regressors.get(k);
			// Prepare training set
			for (int i = 0; i < residualTrain.length; i++) {
				trainSet.get(i).setTarget(residualTrain[i]);
			}
			// Train model
			IntPair term = terms.get(k);
			cutter.setAttIndices(term.v1, term.v2);
			BaggedEnsemble baggedEnsemble = learner.build(bags);
			if (learningRate != 1) {
				for (int i = 0; i < baggedEnsemble.size(); i++) {
					Function2D func = (Function2D) baggedEnsemble.get(i);
					func.multiply(learningRate);
				}
			}
			boostedEnsemble.add(baggedEnsemble);

			// Update residuals
			for (int j = 0; j < residualTrain.length; j++) {
				Instance instance = trainSet.get(j);
				double pred = baggedEnsemble.regress(instance);
				residualTrain[j] -= pred;
			}
			for (int j = 0; j < residualValid.length; j++) {
				Instance instance = validSet.get(j);
				double pred = baggedEnsemble.regress(instance);
				residualValid[j] -= pred;
			}

			double rmse = StatUtils.rms(residualValid);
			rmseList.add(rmse);
			if (verbose) {
				System.out
						.println("Iteration " + iter + " term " + k + ":" + StatUtils.rms(residualTrain) + " " + rmse);
			}
		}

		// Search the best model on validation set
		double min = Double.POSITIVE_INFINITY;
		int idx = -1;
		for (int i = 0; i < rmseList.size(); i++) {
			if (rmseList.get(i) < min) {
				min = rmseList.get(i);
				idx = i;
			}
		}

		// Remove trees
		int n = idx / terms.size();
		int m = idx % terms.size();
		for (int k = 0; k < terms.size(); k++) {
			BoostedEnsemble boostedEnsemble = regressors.get(k);
			for (int i = boostedEnsemble.size(); i > n + 1; i--) {
				boostedEnsemble.removeLast();
			}
			if (k > m) {
				boostedEnsemble.removeLast();
			}
		}

		// Restore targets
		for (int i = 0; i < target.length; i++) {
			trainSet.get(i).setTarget(target[i]);
		}

		// Compress model
		List<Attribute> attributes = trainSet.getAttributes();
		for (int i = 0; i < regressors.size(); i++) {
			BoostedEnsemble boostedEnsemble = regressors.get(i);
			IntPair term = terms.get(i);
			Function2D function = CompressionUtils.compress(term.v1, term.v2, boostedEnsemble);
			Attribute f1 = attributes.get(term.v1);
			Attribute f2 = attributes.get(term.v2);
			int n1 = -1;
			if (f1.getType() == Type.BINNED) {
				n1 = ((BinnedAttribute) f1).getNumBins();
			} else if (f1.getType() == Type.NOMINAL) {
				n1 = ((NominalAttribute) f1).getCardinality();
			}
			int n2 = -1;
			if (f2.getType() == Type.BINNED) {
				n2 = ((BinnedAttribute) f2).getNumBins();
			} else if (f1.getType() == Type.NOMINAL) {
				n2 = ((NominalAttribute) f2).getCardinality();
			}
			Regressor newRegressor = function;
			if (n1 > 0 && n2 > 0) {
				newRegressor = CompressionUtils.convert(n1, n2, function);
			}
			if (indices[i] < 0) {
				gam.add(new int[] { term.v1, term.v2 }, newRegressor);
			} else {
				Regressor regressor = gam.regressors.get(indices[i]);
				if (regressor instanceof Function2D) {
					Function2D func = (Function2D) regressor;
					func.add(function);
				} else if (regressor instanceof Array2D) {
					Array2D ary = (Array2D) regressor;
					ary.add((Array2D) newRegressor);
				} else {
					throw new RuntimeException("Failed to add new regressor");
				}
			}
		}
	}

	/**
	 * Builds a regressor.
	 * 
	 * @param gam the GAM.
	 * @param terms the list of feature interaction pairs.
	 * @param trainSet the training set.
	 * @param maxNumIters the maximum number of iterations.
	 */
	public void buildRegressor(GAM gam, List<IntPair> terms, Instances trainSet, int maxNumIters) {
		List<BoostedEnsemble> regressors = new ArrayList<>();
		int[] indices = new int[terms.size()];
		for (int i = 0; i < indices.length; i++) {
			indices[i] = gam.terms.indexOf(terms.get(i));
			regressors.add(new BoostedEnsemble());
		}

		// Backup targets
		double[] target = new double[trainSet.size()];
		for (int i = 0; i < target.length; i++) {
			target[i] = trainSet.get(i).getTarget();
		}

		// Create bags
		Instances[] bags = Bagging.createBags(trainSet, baggingIters);

		SquareCutter cutter = new SquareCutter();
		BaggedEnsembleLearner learner = new BaggedEnsembleLearner(baggingIters, cutter);

		// Initialize residuals
		double[] residualTrain = new double[trainSet.size()];
		for (int i = 0; i < trainSet.size(); i++) {
			Instance instance = trainSet.get(i);
			residualTrain[i] = instance.getTarget() - gam.regress(instance);
		}

		List<Double> rmseList = new ArrayList<>(maxNumIters);

		// Gradient boosting
		for (int iter = 0; iter < maxNumIters; iter++) {
			int k = iter % terms.size();
			// Derivative to attribute k
			// Equivalent to residual
			BoostedEnsemble boostedEnsemble = regressors.get(k);
			// Prepare training set
			for (int i = 0; i < residualTrain.length; i++) {
				trainSet.get(i).setTarget(residualTrain[i]);
			}
			// Train model
			IntPair term = terms.get(k);
			cutter.setAttIndices(term.v1, term.v2);
			BaggedEnsemble baggedEnsemble = learner.build(bags);
			if (learningRate != 1) {
				for (int i = 0; i < baggedEnsemble.size(); i++) {
					Function2D func = (Function2D) baggedEnsemble.get(i);
					func.multiply(learningRate);
				}
			}
			boostedEnsemble.add(baggedEnsemble);

			// Update residuals
			for (int j = 0; j < residualTrain.length; j++) {
				Instance instance = trainSet.get(j);
				double pred = baggedEnsemble.regress(instance);
				residualTrain[j] -= pred;
			}

			double rmse = StatUtils.rms(residualTrain);
			rmseList.add(rmse);
			if (verbose) {
				System.out
						.println("Iteration " + iter + " term " + k + ":" + StatUtils.rms(residualTrain) + " " + rmse);
			}
		}

		// Restore targets
		for (int i = 0; i < target.length; i++) {
			trainSet.get(i).setTarget(target[i]);
		}

		// Compress model
		List<Attribute> attributes = trainSet.getAttributes();
		for (int i = 0; i < regressors.size(); i++) {
			BoostedEnsemble boostedEnsemble = regressors.get(i);
			IntPair term = terms.get(i);
			Function2D function = CompressionUtils.compress(term.v1, term.v2, boostedEnsemble);
			Attribute f1 = attributes.get(term.v1);
			Attribute f2 = attributes.get(term.v2);
			int n1 = -1;
			if (f1.getType() == Type.BINNED) {
				n1 = ((BinnedAttribute) f1).getNumBins();
			} else if (f1.getType() == Type.NOMINAL) {
				n1 = ((NominalAttribute) f1).getCardinality();
			}
			int n2 = -1;
			if (f2.getType() == Type.BINNED) {
				n2 = ((BinnedAttribute) f2).getNumBins();
			} else if (f1.getType() == Type.NOMINAL) {
				n2 = ((NominalAttribute) f2).getCardinality();
			}
			Regressor newRegressor = function;
			if (n1 > 0 && n2 > 0) {
				newRegressor = CompressionUtils.convert(n1, n2, function);
			}
			if (indices[i] < 0) {
				gam.add(new int[] { term.v1, term.v2 }, newRegressor);
			} else {
				Regressor regressor = gam.regressors.get(indices[i]);
				if (regressor instanceof Function2D) {
					Function2D func = (Function2D) regressor;
					func.add(function);
				} else if (regressor instanceof Array2D) {
					Array2D ary = (Array2D) regressor;
					ary.add((Array2D) newRegressor);
				} else {
					throw new RuntimeException("Failed to add new regressor");
				}
			}
		}
	}

	@Override
	public GAM build(Instances instances) {
		if (pairs == null) {
			int n = instances.dimension();
			pairs = new ArrayList<IntPair>();
			for (int i = 0; i < n; i++) {
				for (int j = i + 1; j < n; j++) {
					pairs.add(new IntPair(i, j));
				}
			}
		}
		if (maxNumIters < 0) {
			maxNumIters = pairs.size() * 20;
		}
		switch (task) {
			case REGRESSION:
				if (validSet != null) {
					buildRegressor(gam, pairs, instances, validSet, maxNumIters);
				} else {
					buildRegressor(gam, pairs, instances, maxNumIters);
				}
				break;
			case CLASSIFICATION:
				if (validSet != null) {
					buildClassifier(gam, pairs, instances, validSet, maxNumIters);
				} else {
					buildClassifier(gam, pairs, instances, maxNumIters);
				}
				break;
			default:
				break;
		}
		return gam;
	}

	static class Options {

		@Argument(name = "-r", description = "attribute file path")
		String attPath = null;

		@Argument(name = "-t", description = "train set path", required = true)
		String trainPath = null;

		@Argument(name = "-v", description = "valid set path")
		String validPath = null;

		@Argument(name = "-i", description = "input model path", required = true)
		String inputModelPath = null;

		@Argument(name = "-o", description = "output model path")
		String outputModelPath = null;

		@Argument(name = "-g", description = "task between classification (c) and regression (r) (default: r)")
		String task = "r";

		@Argument(name = "-I", description = "list of pairwise interactions path", required = true)
		String interactionsPath = null;

		@Argument(name = "-m", description = "maximum number of iterations", required = true)
		int maxNumIters = -1;

		@Argument(name = "-b", description = "bagging iterations (default: 100)")
		int baggingIters = 100;

		@Argument(name = "-s", description = "seed of the random number generator (default: 0)")
		long seed = 0L;

		@Argument(name = "-l", description = "learning rate (default: 0.01)")
		double learningRate = 0.01;

	}

	/**
	 * <p>
	 * 
	 * <pre>
	 * Usage: GA2MLearner
	 * -t	train set path
	 * -i	input model path
	 * -I	list of pairwise interactions path
	 * -m	maximum number of iterations
	 * [-r]	attribute file path
	 * [-v]	valid set path
	 * [-o]	output model path
	 * [-g]	task between classification (c) and regression (r) (default: r)
	 * [-b]	bagging iterations (default: 100)
	 * [-s]	seed of the random number generator (default: 0)
	 * [-l]	learning rate (default: 0.01)
	 * </pre>
	 * 
	 * </p>
	 * 
	 * @param args the command line arguments.
	 * @throws Exception
	 */
	public static void main(String[] args) throws Exception {
		Options opts = new Options();
		CmdLineParser parser = new CmdLineParser(GA2MLearner.class, opts);
		Task task = null;
		try {
			parser.parse(args);
			task = Task.getEnum(opts.task);
		} catch (IllegalArgumentException e) {
			parser.printUsage();
			System.exit(1);
		}

		Random.getInstance().setSeed(opts.seed);

		Instances trainSet = InstancesReader.read(opts.attPath, opts.trainPath);

		List<IntPair> terms = new ArrayList<>();
		BufferedReader br = new BufferedReader(new FileReader(opts.interactionsPath));
		for (;;) {
			String line = br.readLine();
			if (line == null) {
				break;
			}
			String[] data = line.split("\\s+");
			IntPair term = new IntPair(Integer.parseInt(data[0]), Integer.parseInt(data[1]));
			terms.add(term);
		}
		br.close();

		GAM gam = PredictorReader.read(opts.inputModelPath, GAM.class);

		GA2MLearner ga2mLearner = new GA2MLearner();
		ga2mLearner.setBaggingIters(opts.baggingIters);
		ga2mLearner.setGAM(gam);
		ga2mLearner.setMaxNumIters(opts.maxNumIters);
		ga2mLearner.setTask(task);
		ga2mLearner.setLearningRate(opts.learningRate);
		ga2mLearner.setPairs(terms);
		ga2mLearner.setVerbose(true);

		if (opts.validPath != null) {
			Instances validSet = InstancesReader.read(opts.attPath, opts.validPath);
			ga2mLearner.setValidSet(validSet);
		}

		long start = System.currentTimeMillis();
		ga2mLearner.build(trainSet);
		long end = System.currentTimeMillis();
		System.out.println("Time: " + (end - start) / 1000.0);

		if (opts.outputModelPath != null) {
			PredictorWriter.write(gam, opts.outputModelPath);
		}
	}

}