/**
 * 
 */
package org.rulelearn.usecases;

import java.io.FileWriter;
import java.io.IOException;

import org.rulelearn.data.InformationTableBuilder;
import org.rulelearn.data.InformationTableWithDecisionDistributions;
import org.rulelearn.data.ObjectParseException;
import org.rulelearn.rules.ConfidenceRuleFilter;
import org.rulelearn.rules.RuleFilter;
import org.rulelearn.rules.RuleSetWithCharacteristics;
import org.rulelearn.rules.ruleml.RuleMLBuilder;
import org.rulelearn.wrappers.VCDomLEMWrapper;

/**
 * Calculations for bank customer satisfaction data set (4000 customers, divided equally into two classes).
 * 
 * @author Marcin Szeląg (<a href="mailto:marcin.szelag@cs.put.poznan.pl">marcin.szelag@cs.put.poznan.pl</a>)
 */
public class BankCustomerSatisfactionAnalysis {
	
	double consistencyThreshold = 0.01;
	String metadataPath = "src/main/resources/data/json-metadata/bank-churn-4000-v8 metadata.json";
	String dataPath = "src/main/resources/data/json-objects/bank-churn-4000-v8 data.json";
	
	//String ruleSetPath = "src/main/resources/data/ruleml/bank-churn-4000-v8 rules.xml";
	//String ruleSetPath = "src/main/resources/data/ruleml/bank-churn-4000-v8 rules-confidence_ge_0.5.xml";
	String ruleSetPath = "src/main/resources/data/ruleml/bank-churn-4000-v8 rules-confidence_gt_0.5.xml";
	//RuleFilter ruleFilter = new AcceptingRuleFilter();
	//RuleFilter ruleFilter = new ConfidenceRuleFilter(0.5, false);
	RuleFilter ruleFilter = new ConfidenceRuleFilter(0.5, true);

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
		InformationTableWithDecisionDistributions informationTable = null;
		RuleSetWithCharacteristics ruleSetWithCharacteristics = null;
		
		try {
			informationTable = new InformationTableWithDecisionDistributions(InformationTableBuilder.safelyBuildFromJSONFile(metadataPath, dataPath));
		} catch (IOException exception) {
			exception.printStackTrace();
		} catch (ObjectParseException exception) {
			exception.printStackTrace();
		}
		
		if (informationTable != null) { //read succeeded
			System.out.println("Data read."); //!
			VCDomLEMWrapper vcDomLEMWrapper = new VCDomLEMWrapper();
			ruleSetWithCharacteristics = vcDomLEMWrapper.induceRulesWithCharacteristics(informationTable, consistencyThreshold);
			ruleSetWithCharacteristics.setLearningInformationTableHash(informationTable.getHash()); //save data hash along with rules
			
			ruleSetWithCharacteristics = ruleSetWithCharacteristics.filter(ruleFilter); //apply rule filter (replaces rule set)
			
			writeRuleSet2RuleML(ruleSetWithCharacteristics, ruleSetPath);
			System.out.println("Rules written to file " + ruleSetPath); //!
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
	
}
