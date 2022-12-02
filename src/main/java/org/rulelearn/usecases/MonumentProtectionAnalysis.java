/**
 * 
 */
package org.rulelearn.usecases;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

import org.rulelearn.approximations.ClassicalDominanceBasedRoughSetCalculator;
import org.rulelearn.approximations.Union.UnionType;
import org.rulelearn.approximations.Unions;
import org.rulelearn.approximations.UnionsWithSingleLimitingDecision;
import org.rulelearn.approximations.VCDominanceBasedRoughSetCalculator;
import org.rulelearn.classification.SimpleClassificationResult;
import org.rulelearn.classification.SimpleOptimizingRuleClassifier;
import org.rulelearn.classification.SimpleRuleClassifier;
import org.rulelearn.data.Attribute;
import org.rulelearn.data.Decision;
import org.rulelearn.data.EvaluationAttribute;
import org.rulelearn.data.InformationTable;
import org.rulelearn.data.InformationTableBuilder;
import org.rulelearn.data.InformationTableWithDecisionDistributions;
import org.rulelearn.data.ObjectParseException;
import org.rulelearn.data.SimpleDecision;
import org.rulelearn.data.json.AttributeParser;
import org.rulelearn.measures.dominance.EpsilonConsistencyMeasure;
import org.rulelearn.rules.AcceptingRuleFilter;
import org.rulelearn.rules.CompositeRuleCharacteristicsFilter;
import org.rulelearn.rules.ConfidenceRuleFilter;
import org.rulelearn.rules.Rule;
import org.rulelearn.rules.RuleCoverageInformation;
import org.rulelearn.rules.RuleFilter;
import org.rulelearn.rules.RuleSemantics;
import org.rulelearn.rules.RuleSet;
import org.rulelearn.rules.RuleSetWithCharacteristics;
import org.rulelearn.rules.RuleSetWithComputableCharacteristics;
import org.rulelearn.rules.ruleml.RuleMLBuilder;
import org.rulelearn.rules.ruleml.RuleParser;
import org.rulelearn.sampling.CrossValidator;
import org.rulelearn.types.EnumerationFieldFactory;
import org.rulelearn.validation.OrdinalMisclassificationMatrix;
import org.rulelearn.wrappers.VCDomLEMWrapper;

/**
 * Calculations for monument protection data set (113 monuments, 57 suggested for protection, 56 not suggested for protection).
 * 
 * @author Marcin Szeląg (<a href="mailto:marcin.szelag@cs.put.poznan.pl">marcin.szelag@cs.put.poznan.pl</a>)
 */
public class MonumentProtectionAnalysis {
	//PARAM 1
	//double consistencyThreshold = 0.01;
	double consistencyThreshold = 0.0;
	
	final String metadataPath = "src/main/resources/data/json-metadata/zabytki-metadata-Y1-K-numeric-ordinal.json";
	final String dataPath = "src/main/resources/data/json-objects/zabytki-data-noMV.json";
	
	//PARAM 2a
	//String ruleSetPath = "src/main/resources/data/ruleml/bank-churn-4000-v8-"+consistencyThreshold+"-rules.xml";
	//String ruleSetPath = "src/main/resources/data/ruleml/bank-churn-4000-v8-"+consistencyThreshold+"-rules-confidence_ge_0.5.xml";
	String ruleSetPath = "src/main/resources/data/ruleml/zabytki-Y1-K-numeric-ordinal-noMV-"+consistencyThreshold+"-generalized-rules-confidence_gt_0.5.xml";
	
	//PARAM 2b
	String inputRuleSetPath = "src/main/resources/data/ruleml/zabytki-Y1-K-numeric-ordinal-noMV-max6conds-min8relStrength.xml";
	String outputRuleSetPath = "src/main/resources/data/ruleml/zabytki-Y1-K-numeric-ordinal-noMV-max6conds-min8relStrength-calculated.xml";
	
	//PARAM 2c
	//RuleFilter ruleFilter = new AcceptingRuleFilter();
	//RuleFilter ruleFilter = new ConfidenceRuleFilter(0.5, false);
	//RuleFilter ruleFilter = new ConfidenceRuleFilter(0.5, true);
	RuleFilter ruleFilter = CompositeRuleCharacteristicsFilter.of("confidence>0.5");
	
	final int foldsCount = 10;
	final long seeds[] = {0L, 8897335920153900L, 5347765673520470L};
	final int decisionAttributeIndex = 16;
	final String defaultClassificationResultLabel = "yes";
	SimpleClassificationResult defaultClassificationResult;

	/**
	 * Main entry point.
	 * 
	 * @param args command-line arguments (ignored)
	 */
	public static void main(String[] args) {
		long startTime = System.currentTimeMillis();
		(new MonumentProtectionAnalysis()).run();
		long duration = System.currentTimeMillis() - startTime;
		
		System.out.println();
		System.out.println("Total time [ms]: "+duration);
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

			RuleSetWithComputableCharacteristics ruleSetWithCharacteristics = generateAndFilterRules(informationTableWithDecisionDistributions, consistencyThreshold, ruleFilter, "Full data. ");
			
			if (ruleSetWithCharacteristics != null) {
				writeRuleSet2RuleML(ruleSetWithCharacteristics, ruleSetPath); //save rules to disk
				System.out.println(ruleSetWithCharacteristics.size()+" rules written to file "+ruleSetPath+"."); //!
			}
			
			defaultClassificationResult = new SimpleClassificationResult(new SimpleDecision(EnumerationFieldFactory.getInstance().create(
					defaultClassificationResultLabel, (EvaluationAttribute)informationTableWithDecisionDistributions.getAttribute(decisionAttributeIndex)), decisionAttributeIndex));
			
			System.out.println("Default decision: " + defaultClassificationResult.getSuggestedDecision().getEvaluation()); //!
			
			System.out.println();
			System.out.println("-- Misclassification matrix for reclassification:"); //!
			OrdinalMisclassificationMatrix mzeOrdinalMisclassificationMatrix = classify(ruleSetWithCharacteristics, informationTableWithDecisionDistributions, defaultClassificationResult);
			printMisclassificationMatrix(mzeOrdinalMisclassificationMatrix, informationTableWithDecisionDistributions.getOrderedUniqueFullyDeterminedDecisions());
			
			for (long seed : seeds) {
				System.out.println();
				System.out.println("-- Misclassification matrix for cross-validation: (seed="+seed+")"); //!
				
				long startTime = System.currentTimeMillis();
				OrdinalMisclassificationMatrix avgMZEOrdinalMisclassificationMatrix = crossValidate(informationTableWithDecisionDistributions, seed, foldsCount);
				printMisclassificationMatrix(avgMZEOrdinalMisclassificationMatrix, informationTableWithDecisionDistributions.getOrderedUniqueFullyDeterminedDecisions());
				long duration = System.currentTimeMillis() - startTime;
				System.out.println("-- Cross-validation time [ms]: "+duration);
			}
			
			//-----
			
			//calculate missing rule characteristics of already calculated rules, for considered data set 
			RuleSet ruleSet = readRules(metadataPath, inputRuleSetPath);
			
			if (ruleSet != null) {
				RuleSetWithComputableCharacteristics ruleSetWithComputableCharacteristics = transformRuleSet(informationTableWithDecisionDistributions, ruleSet, consistencyThreshold);
				printCoveringRules(informationTableWithDecisionDistributions, ruleSetWithComputableCharacteristics);
				writeRuleSet2RuleML(ruleSetWithComputableCharacteristics, outputRuleSetPath);
				System.out.println("Rules with calculated characteristics written to "+outputRuleSetPath+".");
			}
		}
	}
	
	/**
	 * Prints a 2D matrix indexed by object index and rule index, containing "T" if corresponding object is covered by corresponding rule.
	 * 
	 * @param informationTable information table containing objects to be covered by rules
	 * @param ruleSet rule set containing rules to cover objects
	 */
	void printCoveringRules(InformationTable informationTable, RuleSetWithCharacteristics ruleSet) {
		final class IndexedRule {
			int ruleIndex;
			Rule rule;
			int support;
			
			private IndexedRule(int ruleIndex, Rule rule, int support) {
				this.ruleIndex = ruleIndex;
				this.rule = rule;
				this.support = support;
			}
			
			public String toString() {
				return (ruleIndex + 1)+": "+rule.toString()+" /support="+support+"/";
			}
		}
		
		int objectsCount = informationTable.getNumberOfObjects();
		int rulesCount = ruleSet.size();
		Rule rule;
		int coveringRulesCount;
		int lastCoveringRuleIndex;
		List<IndexedRule> necessaryRules = new ArrayList<>();
		
		System.out.println("-----");
		for (int objectIndex = 0; objectIndex < objectsCount; objectIndex++) {
			coveringRulesCount = 0;
			lastCoveringRuleIndex = -1;
			for (int ruleIndex = 0; ruleIndex < rulesCount; ruleIndex++) {
				rule = ruleSet.getRule(ruleIndex);
				if (rule.covers(objectIndex, informationTable)) {
					System.out.print("T");
					coveringRulesCount++;
					lastCoveringRuleIndex = ruleIndex;
				}
				System.out.print(";");
			} //for
			
			if (coveringRulesCount == 1) { //current object covered by just one rule
				necessaryRules.add(new IndexedRule(lastCoveringRuleIndex, ruleSet.getRule(lastCoveringRuleIndex),
						ruleSet.getRuleCharacteristics(lastCoveringRuleIndex).getSupport()));
			}
			System.out.println();
		}
		
		System.out.println("Necessary rules:");
		for (IndexedRule indexedRule : necessaryRules) {
			System.out.println(indexedRule);
		}
		System.out.println("-----");
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
	 * @param comment introductory comment concerning current run of rule generation algorithm
	 * 
	 * @return rule set with characteristics
	 */
	RuleSetWithComputableCharacteristics generateAndFilterRules(InformationTable informationTable, double consistencyThreshold, RuleFilter ruleFilter, String comment) {
		long startTime = System.currentTimeMillis();
		RuleSetWithComputableCharacteristics ruleSetWithCharacteristics = (new VCDomLEMWrapper()).induceRulesWithCharacteristics(informationTable, consistencyThreshold);
		long duration = System.currentTimeMillis() - startTime;
		
		System.out.println(comment+"Rules' generation time [ms]: "+duration);
		
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
		List<CrossValidator.CrossValidationFold<InformationTable>> folds = crossValidator.splitStratifiedIntoKFolds(informationTableWithDecisionDistributions, foldsCount); //split data in foldsCount folds
		
		final class IndexedFold {
			int index; //starting from 1
			InformationTable trainData;
			InformationTable testData;
			
			public IndexedFold(int foldIndex, InformationTable foldTrainData, InformationTable foldTestData) {
				this.index = foldIndex;
				this.trainData = foldTrainData;
				this.testData = foldTestData;
			}
		}
		
		List<IndexedFold> indexedFolds = new ArrayList<>(foldsCount);
		for (int i = 0; i < foldsCount; i++) {
			indexedFolds.add(new IndexedFold(i+1, folds.get(i).getTrainingTable(), folds.get(i).getValidationTable()));
		}
		
		OrdinalMisclassificationMatrix[] mzeOrdinalMisclassificationMatrices = new OrdinalMisclassificationMatrix[foldsCount];
		OrdinalMisclassificationMatrix avgMZEOrdinalMisclassificationMatrix;
		
//		InformationTable foldTrainData;
//		InformationTable foldTestData;
//		RuleSetWithComputableCharacteristics ruleSetWithCharacteristics;
//		
//		for (int foldIndex = 0; foldIndex < foldsCount; foldIndex++) {
//			foldTrainData = folds.get(foldIndex).getTrainingTable();
//			foldTestData = folds.get(foldIndex).getValidationTable();
//			
//			System.out.println("Starting fold " + (foldIndex + 1) + "/" + foldsCount + ": train="+foldTrainData.getNumberOfObjects()+", test="+foldTestData.getNumberOfObjects()+" objects. ");
//			
//			ruleSetWithCharacteristics = generateAndFilterRules(foldTrainData, consistencyThreshold, ruleFilter,"  Fold "+(foldIndex + 1)+"/"+foldsCount+". ");
//			
//			mzeOrdinalMisclassificationMatrices[foldIndex] = classify(ruleSetWithCharacteristics, foldTestData, defaultClassificationResult);
//		} //for foldIndex
		
		//do in parallel sequences of rule learning on train data and classification with the rules on test data		
		mzeOrdinalMisclassificationMatrices = indexedFolds.parallelStream()
			.map(fold -> {
				System.out.println("Starting fold "+(fold.index)+"/"+foldsCount+": train="+fold.trainData.getNumberOfObjects()+", test="+fold.testData.getNumberOfObjects()+" objects.");
				return classify(generateAndFilterRules(fold.trainData, consistencyThreshold, ruleFilter, "  Fold "+(fold.index)+"/"+foldsCount+". "), fold.testData, defaultClassificationResult);
			})
			.collect(Collectors.toList()).toArray(mzeOrdinalMisclassificationMatrices);
		
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
	 * Writes to file, in RuleML format, given rules (and their characteristics, if present).
	 * 
	 * @param ruleSet set of rules (possibly along with their characteristics)
	 * @param ruleMLFilePath path to disk file where rules (and their characteristics) should be written in RuleML format
	 */
	void writeRuleSet2RuleML(RuleSet ruleSet, String ruleMLFilePath) {
		RuleMLBuilder ruleMLBuilder = new RuleMLBuilder();
		String ruleML = ruleMLBuilder.toRuleMLString(ruleSet, 1);
		
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
	
	/**
	 * Reads rule set with characteristics from a RuleML file.
	 * 
	 * @param attributesFilePath path to metadata file
	 * @param rulesFilePath path to rules file
	 * 
	 * @return rule set with characteristics or {@code null} in case of error
	 */
	RuleSetWithCharacteristics readRules(String attributesFilePath, String rulesFilePath) {
		Attribute [] attributes = null;
		AttributeParser attributeParser = new AttributeParser();
		try (FileReader attributeReader = new FileReader(attributesFilePath)) {
			attributes = attributeParser.parseAttributes(attributeReader);
			if (attributes != null) {
				Map<Integer, RuleSetWithCharacteristics> rules = null;
				RuleParser ruleParser = new RuleParser(attributes);
				try (FileInputStream fileRulesStream = new FileInputStream(rulesFilePath)) {
					rules = ruleParser.parseRulesWithCharacteristics(fileRulesStream);
					if (rules != null) {
						return rules.get(1);
					}
					else {
						//fail("Unable to load RuleML file.");
						return null;
					}
				}
				catch (FileNotFoundException ex) {
					return null;
				}
			}
			else {
				//fail("Unable to load JSON file with meta-data.");
				return null;
			}
		}
		catch (FileNotFoundException ex) {
			return null;
		}
		catch (IOException ex) {
			return null;
		}
	}
	
	/**
	 * Transforms given rule set to rule set with computable characteristics.
	 * 
	 * @param informationTableWithDecisionDistributions learning information table
	 * @param ruleSet rule set (without characteristics)
	 * @param consistencyThreshold consistency threshold for which rules have been induced
	 * 
	 * @return rule set with computable characteristics
	 */
	RuleSetWithComputableCharacteristics transformRuleSet(InformationTableWithDecisionDistributions informationTableWithDecisionDistributions, RuleSet ruleSet, double consistencyThreshold) {
		if (ruleSet != null) {
			//calculate all characteristics
			UnionsWithSingleLimitingDecision unions = new UnionsWithSingleLimitingDecision(informationTableWithDecisionDistributions,
					consistencyThreshold == 0.0 ?
							new ClassicalDominanceBasedRoughSetCalculator() :
							new VCDominanceBasedRoughSetCalculator(EpsilonConsistencyMeasure.getInstance(), consistencyThreshold));
			
			int rulesCount = ruleSet.size();
			Rule[] rules = new Rule[rulesCount];
			RuleCoverageInformation[] ruleCoverageInformationArray = new RuleCoverageInformation[rulesCount];
			UnionType unionType;
			
			for (int i = 0; i < rulesCount; i++) {
				rules[i] = ruleSet.getRule(i);
				unionType = (rules[i].getSemantics() == RuleSemantics.AT_LEAST ? UnionType.AT_LEAST : UnionType.AT_MOST);
						
				ruleCoverageInformationArray[i] = new RuleCoverageInformation(rules[i],
						informationTableWithDecisionDistributions,
						unions.getUnion(unionType, new SimpleDecision(rules[i].getDecision().getLimitingEvaluation(), rules[i].getDecision().getAttributeWithContext().getAttributeIndex())));
			}
			
			RuleSetWithComputableCharacteristics ruleSetWithComputableCharacteristics =
					new RuleSetWithComputableCharacteristics(rules, ruleCoverageInformationArray);
			
			ruleSetWithComputableCharacteristics.calculateAllCharacteristics(); //force calculation of all characteristics
			ruleSetWithComputableCharacteristics.setLearningInformationTableHash(ruleSet.getLearningInformationTableHash()); //copy learning information table hash!
			
			return ruleSetWithComputableCharacteristics;
		} else {
			System.out.println("Transformed rule set is null.");
			return null;
		}
	}
	
}
