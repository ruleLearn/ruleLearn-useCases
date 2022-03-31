/**
 * 
 */
package org.rulelearn.usecases;

import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Random;

import org.rulelearn.approximations.Unions;
import org.rulelearn.approximations.UnionsWithSingleLimitingDecision;
import org.rulelearn.approximations.VCDominanceBasedRoughSetCalculator;
import org.rulelearn.classification.SimpleClassificationResult;
import org.rulelearn.classification.SimpleOptimizingRuleClassifier;
import org.rulelearn.classification.SimpleRuleClassifier;
import org.rulelearn.data.Decision;
import org.rulelearn.data.EvaluationAttribute;
import org.rulelearn.data.InformationTable;
import org.rulelearn.data.InformationTableBuilder;
import org.rulelearn.data.InformationTableWithDecisionDistributions;
import org.rulelearn.data.ObjectParseException;
import org.rulelearn.data.SimpleDecision;
import org.rulelearn.measures.dominance.EpsilonConsistencyMeasure;
import org.rulelearn.rules.AcceptingRuleFilter;
import org.rulelearn.rules.ConfidenceRuleFilter;
import org.rulelearn.rules.RuleFilter;
import org.rulelearn.rules.RuleSetWithCharacteristics;
import org.rulelearn.rules.RuleSetWithComputableCharacteristics;
import org.rulelearn.rules.ruleml.RuleMLBuilder;
import org.rulelearn.sampling.CrossValidator;
import org.rulelearn.types.EnumerationFieldFactory;
import org.rulelearn.validation.OrdinalMisclassificationMatrix;
import org.rulelearn.wrappers.VCDomLEMWrapper;

/**
 * Calculations for bank customer satisfaction data set (4000 customers, divided equally into two classes).
 * 
 * @author Marcin Szeląg (<a href="mailto:marcin.szelag@cs.put.poznan.pl">marcin.szelag@cs.put.poznan.pl</a>)
 */
public class BankCustomerSatisfactionAnalysis {
	
	//PARAM 1
	//double consistencyThreshold = 0.01; //max 20 negative objects in a dominance cone
	double consistencyThreshold = 0.02;  //max 10 negative objects in a dominance cone
	
	final String metadataPath = "src/main/resources/data/json-metadata/bank-churn-4000-v8 metadata.json";
	final String dataPath = "src/main/resources/data/json-objects/bank-churn-4000-v8 data.json";
	
	//PARAM 2a
	//String ruleSetPath = "src/main/resources/data/ruleml/bank-churn-4000-v8-"+consistencyThreshold+"-rules.xml";
	//String ruleSetPath = "src/main/resources/data/ruleml/bank-churn-4000-v8-"+consistencyThreshold+"-rules-confidence_ge_0.5.xml";
	String ruleSetPath = "src/main/resources/data/ruleml/bank-churn-4000-v8-"+consistencyThreshold+"-rules-confidence_gt_0.5.xml";
	
	//PARAM 2b
	//RuleFilter ruleFilter = new AcceptingRuleFilter();
	//RuleFilter ruleFilter = new ConfidenceRuleFilter(0.5, false);
	RuleFilter ruleFilter = new ConfidenceRuleFilter(0.5, true);
	
	final int foldsCount = 10;
	
	final long seed0 = 0L;
	final long seed1 = 5488762120989881L;
	final long seed2 = 4329629961476882L;

	//PARAM 3
	long seed = seed0;
	
	final int decisionAttributeIndex = 11;
	SimpleClassificationResult defaultClassificationResult;
	
	/**
	 * Main entry point.
	 * 
	 * @param args command-line arguments (ignored)
	 */
	public static void main(String[] args) {
		(new BankCustomerSatisfactionAnalysis()).run();
	}
	
	/**
	 * Calculations.
	 */
	void run() {
		InformationTable informationTable = null;
		
		try {
			informationTable = InformationTableBuilder.safelyBuildFromJSONFile(metadataPath, dataPath);
		} catch (IOException exception) {
			exception.printStackTrace();
		} catch (ObjectParseException exception) {
			exception.printStackTrace();
		}
		
		if (informationTable != null) { //read succeeded
			System.out.println("Data read from "+metadataPath+" and "+dataPath+"."); //!
			
			InformationTableWithDecisionDistributions informationTableWithDecisionDistributions = new InformationTableWithDecisionDistributions(informationTable, true);
			
			System.out.println("Consistency threshold: " + consistencyThreshold); //!
			System.out.println("Quality of approximation: " + calculateQualityOfApproximation(informationTableWithDecisionDistributions, consistencyThreshold)); //!
			
			printRuleFilter(ruleFilter); //!

			RuleSetWithComputableCharacteristics ruleSetWithCharacteristics = generateAndFilterRules(informationTableWithDecisionDistributions, consistencyThreshold, ruleFilter);
			
			if (ruleSetWithCharacteristics != null) {
				writeRuleSet2RuleML(ruleSetWithCharacteristics, ruleSetPath); //save rules to disk
				System.out.println(ruleSetWithCharacteristics.size()+" rules written to file " + ruleSetPath + "."); //!
			}
			
			defaultClassificationResult = new SimpleClassificationResult(new SimpleDecision(EnumerationFieldFactory.getInstance().create(
							"0", (EvaluationAttribute)informationTableWithDecisionDistributions.getAttribute(decisionAttributeIndex)), decisionAttributeIndex));
			
			System.out.println("Default decision: " + defaultClassificationResult.getSuggestedDecision().getEvaluation()); //!
			
			System.out.println("-- Misclassification matrix for reclassification:"); //!
			OrdinalMisclassificationMatrix mzeOrdinalMisclassificationMatrix = classify(ruleSetWithCharacteristics, informationTableWithDecisionDistributions, defaultClassificationResult);
			printMisclassificationMatrix(mzeOrdinalMisclassificationMatrix, informationTableWithDecisionDistributions.getOrderedUniqueFullyDeterminedDecisions());
			
			System.out.println("-- Misclassification matrix for cross-validation: (seed="+seed+")"); //!
			
			OrdinalMisclassificationMatrix avgMZEOrdinalMisclassificationMatrix = crossValidate(informationTableWithDecisionDistributions, seed, foldsCount);
			printMisclassificationMatrix(avgMZEOrdinalMisclassificationMatrix, informationTableWithDecisionDistributions.getOrderedUniqueFullyDeterminedDecisions());
		}
	}
	
	/**
	 * Calculates quality of approximation.
	 * 
	 * @param informationTable the data
	 * @return quality of approximation
	 */
	double calculateQualityOfApproximation(InformationTable informationTable, double consistencyThreshold) {
		InformationTableWithDecisionDistributions informationTableWithDecisionDistributions = (informationTable instanceof InformationTableWithDecisionDistributions ?
				(InformationTableWithDecisionDistributions)informationTable : new InformationTableWithDecisionDistributions(informationTable, true));
		
		Unions unions = new UnionsWithSingleLimitingDecision(informationTableWithDecisionDistributions, 
				   new VCDominanceBasedRoughSetCalculator(EpsilonConsistencyMeasure.getInstance(), consistencyThreshold));
		
		return unions.getQualityOfApproximation();
	}
	
	/**
	 * Generates rules for given consistency threshold and applies given filter. 
	 * 
	 * @param informationTable the data
	 * @param consistencyThreshold threshold for measure epsilon
	 * @param ruleFilter rule filter applied to generated rules
	 * 
	 * @return rule set with characteristics
	 */
	RuleSetWithComputableCharacteristics generateAndFilterRules(InformationTable informationTable, double consistencyThreshold, RuleFilter ruleFilter) {
		RuleSetWithComputableCharacteristics ruleSetWithCharacteristics = (new VCDomLEMWrapper()).induceRulesWithCharacteristics(informationTable, consistencyThreshold);
		ruleSetWithCharacteristics.setLearningInformationTableHash(informationTable.getHash()); //save data hash along with rules
		
		return ruleSetWithCharacteristics.filter(ruleFilter); //apply rule filter (replaces rule set, retains hash)
	}
	
	/**
	 * Learns decision rules on the train data, and applies them on the test data (possibly the same data), using given default classification result.
	 * 
	 * @param ruleSetWithCharacteristics rules used to classify objects from the test data
	 * @param testData test data
	 * @param defaultClassificationResult default classification result
	 * 
	 * @return ordinal misclassification matrix summarizing classification of test objects by rules
	 */
	OrdinalMisclassificationMatrix classify(RuleSetWithComputableCharacteristics ruleSetWithCharacteristics, InformationTable testData, SimpleClassificationResult defaultClassificationResult) {
		SimpleRuleClassifier simpleRuleClassifier = new SimpleOptimizingRuleClassifier(ruleSetWithCharacteristics, defaultClassificationResult);

		int testDataSize = testData.getNumberOfObjects(); //it is assumed that testDataSize > 0
		Decision[] orderOfDecisions = testData.getOrderedUniqueFullyDeterminedDecisions();
		Decision[] originalDecisions = testData.getDecisions(true);
		SimpleDecision[] assignedDecisions = new SimpleDecision[testDataSize]; //will contain assigned decisions
	
		for (int testObjectIndex = 0; testObjectIndex < testDataSize; testObjectIndex++) {
			assignedDecisions[testObjectIndex] = simpleRuleClassifier.classify(testObjectIndex, testData).getSuggestedDecision();
		}
		
		return new OrdinalMisclassificationMatrix(orderOfDecisions, originalDecisions, assignedDecisions);
	}
	
	/**
	 * Calculations ordinal misclassification matrix resulting from k-fold cross-validation.
	 * 
	 * @param informationTable the data
	 * @param seed random generator seed
	 * @param foldsCount number of cross-validation folds
	 * 
	 * @return ordinal misclassification matrix resulting from k-fold cross-validation
	 */
	OrdinalMisclassificationMatrix crossValidate(InformationTable informationTable, long seed, int foldsCount) {
		InformationTableWithDecisionDistributions informationTableWithDecisionDistributions = (informationTable instanceof InformationTableWithDecisionDistributions ?
				(InformationTableWithDecisionDistributions)informationTable : new InformationTableWithDecisionDistributions(informationTable, true));
		
		CrossValidator crossValidator = new CrossValidator(new Random());
		crossValidator.setSeed(seed);
		List<CrossValidator.CrossValidationFold<InformationTable>> folds = crossValidator.splitStratifiedIntoKFold(informationTableWithDecisionDistributions, foldsCount); //split data in foldsCount folds
		
		InformationTable foldTrainData;
		InformationTable foldTestData;
		
		OrdinalMisclassificationMatrix[] mzeOrdinalMisclassificationMatrices = new OrdinalMisclassificationMatrix[foldsCount];
		OrdinalMisclassificationMatrix avgMZEOrdinalMisclassificationMatrix;
		
		RuleSetWithComputableCharacteristics ruleSetWithCharacteristics;
		
		for (int foldIndex = 0; foldIndex < foldsCount; foldIndex++) {
			System.out.println("Starting fold " + (foldIndex + 1) + "/" + foldsCount);
			
			foldTrainData = folds.get(foldIndex).getTrainingTable();
			foldTestData = folds.get(foldIndex).getValidationTable();
			
			ruleSetWithCharacteristics = generateAndFilterRules(foldTrainData, consistencyThreshold, ruleFilter);
			
			mzeOrdinalMisclassificationMatrices[foldIndex] = classify(ruleSetWithCharacteristics, foldTestData, defaultClassificationResult);
		} //for foldIndex
		
		avgMZEOrdinalMisclassificationMatrix = new OrdinalMisclassificationMatrix(true, informationTableWithDecisionDistributions.getOrderedUniqueFullyDeterminedDecisions(),
				mzeOrdinalMisclassificationMatrices); //accumulated misclassification matrix
		
		return avgMZEOrdinalMisclassificationMatrix;
	}
	
	/**
	 * Prints to system output information about employed rule filter.
	 * 
	 * @param ruleFilter employed rule filter
	 */
	void printRuleFilter(RuleFilter ruleFilter) {
		if (ruleFilter instanceof AcceptingRuleFilter) {
			System.out.println("Rule filter: accepting rule filter.");
		} else {
			if (ruleFilter instanceof ConfidenceRuleFilter) { //Confidence rule filter
				ConfidenceRuleFilter confidenceRuleFilter = (ConfidenceRuleFilter)ruleFilter;
				System.out.println("Rule filter: confidence rule filter (confidence "+(confidenceRuleFilter.getStrictComparison() ? "> " : ">= ")+confidenceRuleFilter.getConfidenceThreshold()+")");
			}
		}
	}
	
	/**
	 * Writes to file, in RuleML format, given rules and their characteristics.
	 * 
	 * @param ruleSetWithCharacteristics set of rules along with their characteristics
	 * @param ruleMLFilePath path to disk file where rules and their characteristics should be written in RuleML format
	 */
	void writeRuleSet2RuleML(RuleSetWithCharacteristics ruleSetWithCharacteristics, String ruleMLFilePath) {
		RuleMLBuilder ruleMLBuilder = new RuleMLBuilder();
		String ruleML = ruleMLBuilder.toRuleMLString(ruleSetWithCharacteristics, 1);
		
		try (FileWriter fileWriter = new FileWriter(ruleMLFilePath)) {
			fileWriter.write(ruleML);
			fileWriter.close();
		}
		catch (IOException exception) {
			exception.printStackTrace();
		}
	}
	
	/**
	 * Prints given misclassification matrix to standard output.
	 * 
	 * @param misclassificationMatrix misclassification matrix to print to system output 
	 * @param orderOfDecisions order of decisions (and thus order of rows and columns of the matrix)
	 */
	void printMisclassificationMatrix(OrdinalMisclassificationMatrix misclassificationMatrix, Decision[] orderOfDecisions) {
		System.out.println("Accuracy: " + misclassificationMatrix.getAccuracy()); //!
		System.out.println("MAE: " + misclassificationMatrix.getMAE()); //!
		System.out.println("RMSE: " + misclassificationMatrix.getRMSE()); //!
		System.out.println("GMean: " + misclassificationMatrix.getGmean()); //!
		System.out.println("Number of correct assignments: " + misclassificationMatrix.getNumberOfCorrectAssignments()); //!
		System.out.println("Number of incorrect assignments: " + misclassificationMatrix.getNumberOfIncorrectAssignments()); //!
		System.out.println("Number of objects with assigned decision: " + misclassificationMatrix.getNumberObjectsWithAssignedDecision()); //!
		
		for (int i = 0; i < orderOfDecisions.length; i++) {
			for (int j = 0; j < orderOfDecisions.length; j++) {
				System.out.println("Misclassification matrix cell for row '"+orderOfDecisions[i].getEvaluation(decisionAttributeIndex)
						+"' and column '"+orderOfDecisions[j].getEvaluation(decisionAttributeIndex)+"': "
						+ misclassificationMatrix.getValue(orderOfDecisions[i], orderOfDecisions[j])); //!
			}
		}
	}
	
}
