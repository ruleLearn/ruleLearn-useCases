/**
 * 
 */
package org.rulelearn.usecases;

import java.io.IOException;

import org.rulelearn.approximations.Union;
import org.rulelearn.approximations.UnionWithSingleLimitingDecision;
import org.rulelearn.approximations.Unions;
import org.rulelearn.approximations.UnionsWithSingleLimitingDecision;
import org.rulelearn.approximations.VCDominanceBasedRoughSetCalculator;
import org.rulelearn.data.InformationTable;
import org.rulelearn.data.InformationTableBuilder;
import org.rulelearn.data.InformationTableWithDecisionDistributions;
import org.rulelearn.data.ObjectParseException;
import org.rulelearn.measures.dominance.EpsilonConsistencyMeasure;

import it.unimi.dsi.fastutil.ints.IntSet;

/**
 * Tests calculation of approximations and regions of unions of decision classes.
 * Concerns the case where despite pure DRSA, positive region contains (due to presence in data of missing value of type mv_2)
 * more objects than lower approximation. 
 * 
 * @author Marcin Szeląg (<a href="mailto:marcin.szelag@cs.put.poznan.pl">marcin.szelag@cs.put.poznan.pl</a>)
 */
public class Approximations {
	
	double consistencyThreshold = 0.00;

	final String metadataPath = "src/main/resources/data/json-metadata/students-metadata.json";
	final String dataPath = "src/main/resources/data/csv/students-data.csv";
	
	final int decisionAttributeIndex = 12;
	
	/**
	 * Main entry point.
	 * 
	 * @param args command-line arguments (ignored)
	 */
	public static void main(String[] args) {
		(new Approximations()).run();
	}
	
	/**
	 * Calculations.
	 */
	void run() {
		InformationTable informationTable = null;
		
		try {
			informationTable = InformationTableBuilder.safelyBuildFromCSVFile(metadataPath, dataPath, false, ';');
		} catch (IOException exception) {
			exception.printStackTrace();
		} catch (ObjectParseException exception) {
			exception.printStackTrace();
		}
		
		if (informationTable != null) { //read succeeded
			System.out.println("Data read from "+metadataPath+" and "+dataPath+"."); //!
			
			InformationTableWithDecisionDistributions informationTableWithDecisionDistributions = new InformationTableWithDecisionDistributions(informationTable, true);
			
			System.out.println("Consistency threshold: " + consistencyThreshold); //!
			
			Unions unions = calculateUnions(informationTableWithDecisionDistributions, consistencyThreshold);
			System.out.println("Quality of classification: " + unions.getQualityOfApproximation()); //!
			
			Union union;
			
			union = unions.getUpwardUnions()[0];
			
			System.out.println("Union \"at least " + ((UnionWithSingleLimitingDecision)union).getLimitingDecision().getEvaluation(decisionAttributeIndex) + "\"");
			System.out.println("Lower approximation size: " + union.getLowerApproximation().size());
			System.out.println("Positive region size: " + union.getPositiveRegion().size());
			
			IntSet lowerApproximation = union.getLowerApproximation();
			IntSet positiveRegion = union.getPositiveRegion();
			
			for (int i : positiveRegion) {
				if (!lowerApproximation.contains(i)) {
					System.out.println(i); //185 in RuLeStudio, 184 in memory
				}
			}
		}
	}
	
	/**
	 * Calculates unions.
	 * 
	 * @param informationTable the data
	 * @return unions defined for the given data
	 */
	Unions calculateUnions(InformationTable informationTable, double consistencyThreshold) {
		InformationTableWithDecisionDistributions informationTableWithDecisionDistributions = (informationTable instanceof InformationTableWithDecisionDistributions ?
				(InformationTableWithDecisionDistributions)informationTable : new InformationTableWithDecisionDistributions(informationTable, true));
		
		Unions unions = new UnionsWithSingleLimitingDecision(informationTableWithDecisionDistributions, 
				   new VCDominanceBasedRoughSetCalculator(EpsilonConsistencyMeasure.getInstance(), consistencyThreshold));
		
		return unions;
	}

}
