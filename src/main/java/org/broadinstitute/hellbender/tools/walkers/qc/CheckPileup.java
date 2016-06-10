package org.broadinstitute.hellbender.tools.walkers.qc;

import org.broadinstitute.hellbender.cmdline.Argument;
import org.broadinstitute.hellbender.cmdline.CommandLineProgramProperties;
import org.broadinstitute.hellbender.cmdline.StandardArgumentDefinitions;
import org.broadinstitute.hellbender.cmdline.programgroups.QCProgramGroup;
import org.broadinstitute.hellbender.engine.*;
import org.broadinstitute.hellbender.exceptions.UserException;
import org.broadinstitute.hellbender.utils.IntervalUtils;
import org.broadinstitute.hellbender.utils.codecs.sampileup.SAMPileupFeature;
import org.broadinstitute.hellbender.utils.pileup.ReadPileup;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintStream;
import java.util.List;

/**
 * Compare GATK's internal pileup to a reference Samtools pileup
 *
 * <p>
 *     At every locus in the input set, compares the mpileup data (reference base, aligned base from
 *     each overlapping read, and quality score) generated internally by GATK to a reference pileup data generated
 *     by Samtools. Only consider single-sample mpileup format.
 *     See the <a href="http://samtools.sourceforge.net/pileup.shtml">Pileup format documentation</a> for more details
 *     on the format
 * </p>
 *
 * <h3>Input</h3>
 * <p>
 *     A BAM file containing your aligned sequence data and a mpileup file generated by Samtools covering the region you
 *     want to examine.
 * </p>
 *
 * <h3>Output</h3>
 * <p>
 *     A text file listing mismatches between the input mpileup and the GATK's internal pileup. If there are no mismatches,
 *     the output file is empty.
 * </p>
 *
 * <h3>Usage example</h3>
 * <pre>
 * ./gatk-launch CheckPileup \
 *   -R reference.fasta \
 *   -I your_data.bam \
 *   --pileup pileup_file.pileup \
 *   -L chr1:257-275 \
 *   -O output_file_name
 * </pre>
 */
@CommandLineProgramProperties(
    summary = "At every locus in the input set, compares the pileup data (reference base, aligned base from each overlapping"
        + "read, and quality score) generated internally by GATK to a reference pileup data generated by Samtools.",
    oneLineSummary = "Compare GATK's internal pileup to a reference Samtools mpileup",
    programGroup = QCProgramGroup.class)
public final class CheckPileup extends LocusWalker {

    /**
     * This is the existing mpileup against which we'll compare GATK's internal pileup at each genome position in the desired interval.
     */
    @Argument(fullName = "pileup", shortName = "pileup", doc="Pileup generated by Samtools")
    public FeatureInput<SAMPileupFeature> mpileup;

    @Argument(fullName = StandardArgumentDefinitions.OUTPUT_LONG_NAME, shortName = StandardArgumentDefinitions.OUTPUT_SHORT_NAME, doc = "Output file (if not provided, defaults to STDOUT).", optional = true)
    public File outFile = null;

    /**
     * By default the program will quit if it encounters an error (such as missing truth data for a given position).
     * Use this flag to override the default behavior; the program will then simply print an error message and move on
     * to the next position.
     */
    @Argument(fullName="continue_after_error", doc="Continue after encountering an error", optional=true)
    public boolean continueAfterAnError = false;

    @Override
    public boolean requiresReference() {
        return true;
    }

    private long nLoci = 0;
    private long nBases = 0;
    private PrintStream out;

    @Override
    public void onTraversalStart() {
        try {
            out = (outFile == null) ? System.out : new PrintStream(outFile);
        } catch (FileNotFoundException e) {
            throw new UserException.CouldNotCreateOutputFile(outFile, e.getMessage());
        }
    }

    public void apply(final AlignmentContext context, final ReferenceContext ref, final FeatureContext featureContext) {
        final ReadPileup pileup = context.getBasePileup();
        final SAMPileupFeature truePileup = getTruePileup(featureContext);

        if ( truePileup == null ) {
            out.printf("No truth pileup data available at %s%n", pileup.getPileupString((char) ref.getBase()));
            if ( !continueAfterAnError) {
                throw new UserException.BadInput(
                        String.format("No pileup data available at %s given GATK's output of %s -- this walker requires samtools mpileup data over all bases",
                        context.getLocation(), new String(pileup.getBases())));
            }
        } else {
            final String pileupDiff = pileupDiff(pileup, truePileup);
            if ( pileupDiff != null ) {
                out.printf("%s vs. %s%n", pileup.getPileupString((char) ref.getBase()), truePileup.getPileupString());
                if ( !continueAfterAnError) {
                    throw new UserException.BadInput(String.format("The input pileup doesn't match the GATK's internal pileup: %s", pileupDiff));
                }
            }
        }
        nLoci++;
        nBases += pileup.size();
    }

    public String pileupDiff(final ReadPileup a, final SAMPileupFeature b) {
        // compare sizes
        if ( a.size() != b.size() ) {
            return String.format("Sizes not equal: %s vs. %s", a.size(), b.size());
        }
        // compare locations
        if (IntervalUtils.compareLocatables(a.getLocation(), b, getReferenceDictionary()) != 0 ) {
            return String.format("Locations not equal: %s vs. %s", a.getLocation(), b);
        }
        // compare bases
        final String aBases = new String(a.getBases());
        final String bBases = b.getBasesString();
        if ( ! aBases.toUpperCase().equals(bBases.toUpperCase()) ) {
            return String.format("Bases not equal: %s vs. %s", aBases, bBases);
        }

        // compare the qualities
        final String aQuals = new String(a.getBaseQuals());
        final String bQuals = new String(b.getBaseQuals());
        if ( ! aQuals.equals(bQuals) ) {
            return String.format("Quals not equal: %s vs. %s", aQuals, bQuals);
        }
        return null;
    }

    /**
     * Extracts the true pileup data from the given mpileup.
     * @param featureContext Features spanning the current locus.
     * @return true pileup data; {@code null} if not covered
     */
    private SAMPileupFeature getTruePileup( final FeatureContext featureContext ) {
        final List<SAMPileupFeature> features = featureContext.getValues(mpileup);
        return (features.isEmpty()) ? null : features.get(0);
    }

    @Override
    public Object onTraversalSuccess() {
        return String.format("Validated %d sites covered by %d bases%n", nLoci, nBases);
    }

    @Override
    public void closeTool() {
        out.close();
    }
}