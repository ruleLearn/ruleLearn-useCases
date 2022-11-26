/**
 * 
 */
package org.rulelearn.usecases;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.rulelearn.classification.SimpleClassificationResult;
import org.rulelearn.classification.SimpleOptimizingRuleClassifier;
import org.rulelearn.classification.SimpleRuleClassifier;
import org.rulelearn.data.Decision;
import org.rulelearn.data.InformationTable;
import org.rulelearn.data.InformationTableWithDecisionDistributions;
import org.rulelearn.data.SimpleDecision;
import org.rulelearn.rules.RuleSetWithComputableCharacteristics;
import org.rulelearn.sampling.CrossValidator;
import org.rulelearn.validation.OrdinalMisclassificationMatrix;

/**
 * Performs cross-validation using an {@link InformationTable information table}.
 * 
 * @author Marcin Szeląg (<a href="mailto:marcin.szelag@cs.put.poznan.pl">marcin.szelag@cs.put.poznan.pl</a>)
 */
public class CrossValidation {

	/**
	 * Application entry point.
	 * 
	 * @param args input arguments of this application (ignored)
	 */
	public static void main(String[] args) {
		//TODO: call crossValidate with proper arguments
	}

	/**
	 * Performs k-fold cross-validation for a given data set
	 * 
	 * @param informationTable information table containing considered objects
	 * @param foldsCount number of folds (typically 10)
	 * @param epsilonThreshold threshold for consistency measure epsilon, defining lower approximations and decision rules
	 */
	public static void crossValidate(InformationTable informationTable, int foldsCount, double epsilonThreshold) { //TODO: use as explicit parameter also classification quality measure (accuracy or MAE) and indication of classifier to be used
		//TODO: validate parameters
		
		InformationTableWithDecisionDistributions informationTableWithDistributions = new InformationTableWithDecisionDistributions(informationTable, true);

		CrossValidator crossValidator = new CrossValidator(ThreadLocalRandom.current()); //TODO?: use seed-enabled random generator
		List<CrossValidator.CrossValidationFold<InformationTable>> folds = crossValidator.splitStratifiedIntoKFolds(informationTableWithDistributions, foldsCount); //split train data in foldsCount folds

		@SuppressWarnings("unused")
		InformationTableWithDecisionDistributions foldTrainDataWithDistributions;
		InformationTable foldTestData;
		
		OrdinalMisclassificationMatrix[] ordinalMisclassificationMatrices = new OrdinalMisclassificationMatrix[foldsCount];

		for (int foldIndex = 0; foldIndex < foldsCount; foldIndex++) { //chose internal fold on train data
			foldTrainDataWithDistributions = new InformationTableWithDecisionDistributions(folds.get(foldIndex).getTrainingTable(), true);

			RuleSetWithComputableCharacteristics ruleSet = null; //TODO: null assigned for brevity; instead one should calculate ruleSet for fold train data and chosen epsilon measure threshold
			SimpleClassificationResult defaultClassificationResult = null; //TODO: null assigned for brevity; instead one should set defaultClassificationResult based on fold train data and chosen classification quality measure (accuracy or MAE) 

			foldTestData = folds.get(foldIndex).getValidationTable();
			int foldTestDataSize = foldTestData.getNumberOfObjects();

			SimpleRuleClassifier simpleRuleClassifier = new SimpleOptimizingRuleClassifier(ruleSet, defaultClassificationResult); //exemplary classifier
			SimpleDecision[] assignedDecisions = new SimpleDecision[foldTestDataSize]; //will contain assigned decisions

			//classify all test data objects
			for (int testObjectIndex = 0; testObjectIndex < foldTestDataSize; testObjectIndex++) {
				assignedDecisions[testObjectIndex] = simpleRuleClassifier.classify(testObjectIndex, foldTestData).getSuggestedDecision();
			}

			Decision[] orderOfDecisions = informationTableWithDistributions.getOrderedUniqueFullyDeterminedDecisions();
			OrdinalMisclassificationMatrix ordinalMisclassificationMatrix = new OrdinalMisclassificationMatrix(orderOfDecisions, foldTestData.getDecisions(), assignedDecisions); //construct misclassification matrix using original and assigned decisions of test objects
			ordinalMisclassificationMatrices[foldIndex] = ordinalMisclassificationMatrix; //remember misclassification matrix for current fold
		}

		OrdinalMisclassificationMatrix avgOrdinalMisclassificationMatrix = new OrdinalMisclassificationMatrix(informationTableWithDistributions.getOrderedUniqueFullyDeterminedDecisions(), ordinalMisclassificationMatrices); //average all classification matrices calculated for different folds

		System.out.println(avgOrdinalMisclassificationMatrix.getAccuracy()); //make use of calculated average classification accuracy (e.g., print it)
		System.out.println(avgOrdinalMisclassificationMatrix.getMAE()); //make use of calculated MAE (e.g., print it)
	}

}
