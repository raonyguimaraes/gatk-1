package org.broadinstitute.hellbender.tools.spark.sv;

import com.esotericsoftware.kryo.DefaultSerializer;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.google.common.annotations.VisibleForTesting;
import org.broadinstitute.hellbender.utils.SimpleInterval;
import org.broadinstitute.hellbender.utils.Utils;
import scala.Tuple2;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Holds information about a split alignment of a contig, which may represent an SV breakpoint. Each ChimericAlignment
 * represents the junction on the contig of two aligned regions. For example, if a contig aligns to three different regions
 * of the genome (with one primary and two supplementary alignment records), there will be two ChimericAlignment
 * objects created, one to represent each junction between alignment regions:
 *
 * Example Contig:
 * ACTGACTGCACTGACTGCACTGACTGCACTGACTGCACTGACTGCACTGACTGCACTGACTGCACTGACTGCACTGACTGCACTGACTGCACTGACTGCACTGACTG
 * Alignment regions:
 * |---------1:100-200------------|
 *                                 |----------2:100-200------------------|
 *                                                                       |----------3:100-200-----------------|
 * Assembled breakpoints:
 * 1) links 1:100-200 to 2:100-200
 * 2) links 2:100-200 to 3:100-200
 *
 * Inserted sequence contains portions of the contig that are aligned to neither region, and therefore may be inserted in
 * the sample. For example, a translocation breakpoint with a micro-insertion:
 *
 * Contig:
 * ACTGACTGACTGACTGACTGACTGACTGACTGACTGACTGACTG
 * Alignment regions:
 * |-----1:100-200-------|
 *                          |----2:100-200-----|
 * Inserted sequence:
 *  GA
 *
 * Homology represents ambiguity about the exact location of the breakpoint. For example, in this case one alignment
 * region ends with "AC" and the next begins with AC, so we don't know if the AC truly belongs with the first or
 * second alignment region.
 *
 * Contig:
 * ACTGACTGACTGACTGACTGACTGACTGACTGACTGACTGACTG
 * Alignment regions:
 * |-----1:100-200-------|
 *                    |-----2:100-200----------|
 * Homology:
 *  AC
 */
@DefaultSerializer(ChimericAlignment.Serializer.class)
class ChimericAlignment {

    final AlignmentRegion regionWithLowerCoordOnContig;
    final AlignmentRegion regionWithHigherCoordOnContig;

    final StrandSwitch strandSwitch;
    final boolean isForwardStrandRepresentation;

    final byte[] contigSeq;
    final List<String> insertionMappings;

    enum StrandSwitch {
        NO_SWITCH, FORWARD_TO_REVERSE, REVERSE_TO_FORWARD;
    }

    // TODO: 12/14/16 Skipping simple translocation events
    /**
     * Parse all alignment records for a single locally-assembled contig and generate chimeric alignments if available.
     * Applies certain filters to skip the input alignment regions that are:
     *     1) if the alignment region's mapping quality is below a certain threshold, it is skipped
     *     2) if the alignment region is too small, it is skipped
     * If the alignment region passes the above two filters and the next alignment region could be treated as potential inserted sequence,
     * note down the mapping & alignment information of that region and skip it
     *
     * @param input     made of ({alignmentRegions}, sequence) of a signalling locally-assembled contig
     *
     * @return          the chimeric alignments of this sequence (empty if the sequence does not have any alignments)
     */
    @VisibleForTesting
    static List<ChimericAlignment> fromSplitAlignments(final Tuple2<Iterable<AlignmentRegion>, byte[]> input) {

        final byte[] sequence = input._2;
        final List<AlignmentRegion> alignmentRegionsSortedByContigCoord = StreamSupport.stream(input._1().spliterator(), false).sorted(Comparator.comparing(a -> a.startInAssembledContig)).collect(Collectors.toList());
        if (alignmentRegionsSortedByContigCoord.size() < 2) {
            return new ArrayList<>();
        }

        final List<ChimericAlignment> results = new ArrayList<>(alignmentRegionsSortedByContigCoord.size() - 1);
        final List<String> insertionAlignmentRegions = new ArrayList<>();

        final Iterator<AlignmentRegion> iterator = alignmentRegionsSortedByContigCoord.iterator();

        // fast forward to the first alignment region with high MapQ
        AlignmentRegion current = iterator.next();
        while (mapQualTooLow(current) && iterator.hasNext()) {
            current = iterator.next();
        }

        // then iterate over the AR's in pair to identify CA's.
        while ( iterator.hasNext() ) {
            final AlignmentRegion next = iterator.next();
            if (firstAlignmentIsTooShort(current, next, SVConstants.CallingStepConstants.DEFAULT_MIN_ALIGNMENT_LENGTH)) {
                continue;
            } else if (nextAlignmentMayBeNovelInsertion(current, next, SVConstants.CallingStepConstants.DEFAULT_MIN_ALIGNMENT_LENGTH)) {
                if (iterator.hasNext()) {
                    insertionAlignmentRegions.add(next.toPackedString());
                    continue;
                } else {
                    break;
                }
            }

            final ChimericAlignment ca = new ChimericAlignment(current, next, sequence, insertionAlignmentRegions);

            final boolean involvesRefIntervalSwitch = involvesRefPositionSwitch(ca.regionWithLowerCoordOnContig, ca.regionWithHigherCoordOnContig);
            final boolean isNotSimpleTranslocation = isNotSimpleTranslocation(ca.regionWithLowerCoordOnContig, ca.regionWithHigherCoordOnContig, ca.strandSwitch, involvesRefIntervalSwitch);
            if (isNotSimpleTranslocation)
                results.add(ca);

            current = next;
        }

        return results;
    }

    // TODO: 11/22/16 it might also be suitable to consider the reference context this alignment region is mapped to and not simply apply a hard filter (need to think about how to test)
    static boolean mapQualTooLow(final AlignmentRegion next) {
        return next.mapQual < SVConstants.CallingStepConstants.CHIMERIC_ALIGNMENTS_HIGHMQ_THRESHOLD;
    }

    @VisibleForTesting
    static boolean firstAlignmentIsTooShort(final AlignmentRegion first, final AlignmentRegion second,
                                            final Integer minAlignLength) {
        return first.referenceInterval.size() - SVVariantCallerUtils.overlapOnContig(first, second) < minAlignLength;
    }

    /**
     * To implement the idea that for two consecutive alignment regions of a contig, the one with higher reference coordinate might be a novel insertion.
     */
    @VisibleForTesting
    static boolean nextAlignmentMayBeNovelInsertion(final AlignmentRegion current, final AlignmentRegion next,
                                                    final Integer minAlignLength) {
        return mapQualTooLow(next) ||                               // inserted sequence might have low mapping quality
                firstAlignmentIsTooShort(next, current, minAlignLength) ||     // inserted sequence might be very small
                current.referenceInterval.contains(next.referenceInterval) ||   // one might completely contain the other
                next.referenceInterval.contains(current.referenceInterval);
    }

    /**
     * Construct a new ChimericAlignment from two AlignmentRegions.
     * Assumes {@code regionWithLowerCoordOnContig} has a lower {@link AlignmentRegion#startInAssembledContig} than {@code regionWithHigherCoordOnContig},
     * and input alignment regions are NOT completely enclosed in the other.
     */
    @VisibleForTesting
    ChimericAlignment(final AlignmentRegion regionWithLowerCoordOnContig, final AlignmentRegion regionWithHigherCoordOnContig,
                      final byte[] contigSequence, final List<String> insertionMappings) {

        final String assemblyId = regionWithLowerCoordOnContig.assemblyId;
        final String contigId = regionWithLowerCoordOnContig.contigId;
        Utils.validateArg(assemblyId.equals(regionWithHigherCoordOnContig.assemblyId), "two alignment regions used to construct chimeric alignment are not from the same local assembly.");
        Utils.validateArg(contigId.equals(regionWithHigherCoordOnContig.contigId), "two alignment regions used to construct chimeric alignment are not from the same assembled contig.");

        this.regionWithLowerCoordOnContig = regionWithLowerCoordOnContig;
        this.regionWithHigherCoordOnContig = regionWithHigherCoordOnContig;
        Utils.validateArg(!regionWithLowerCoordOnContig.referenceInterval.contains(regionWithHigherCoordOnContig.referenceInterval) && !regionWithHigherCoordOnContig.referenceInterval.contains(regionWithLowerCoordOnContig.referenceInterval),
                "one alignment region contains the other, which is wrong " + regionWithLowerCoordOnContig.toPackedString() + regionWithHigherCoordOnContig.toPackedString());

        this.strandSwitch = determineStrandSwitch(regionWithLowerCoordOnContig, regionWithHigherCoordOnContig);
        final boolean involvesRefIntervalSwitch = involvesRefPositionSwitch(regionWithLowerCoordOnContig, regionWithHigherCoordOnContig);
        this.isForwardStrandRepresentation = isForwardStrandRepresentation(regionWithLowerCoordOnContig, regionWithHigherCoordOnContig, this.strandSwitch, involvesRefIntervalSwitch);

        this.contigSeq = contigSequence;

        this.insertionMappings = insertionMappings;
    }

    @VisibleForTesting
    static StrandSwitch determineStrandSwitch(final AlignmentRegion first, final AlignmentRegion second) {
        if (first.forwardStrand == second.forwardStrand) {
            return StrandSwitch.NO_SWITCH;
        } else {
            return first.forwardStrand ? StrandSwitch.FORWARD_TO_REVERSE : StrandSwitch.REVERSE_TO_FORWARD;
        }
    }

    /**
     * Determine if the region that maps to a lower coordinate on the contig also maps to a lower coordinate on the reference.
     */
    @VisibleForTesting
    static boolean involvesRefPositionSwitch(final AlignmentRegion regionWithLowerCoordOnContig,
                                             final AlignmentRegion regionWithHigherCoordOnContig) {

        return regionWithHigherCoordOnContig.referenceInterval.getStart() < regionWithLowerCoordOnContig.referenceInterval.getStart();
    }

    // TODO: 12/15/16 simple translocations are defined here and at this time as inter-chromosomal ones and
    //                intra-chromosomal translocations that involve strand switch
    //                to get the 2nd case right, we need evidence flanking both sides of the inversion, and that could be difficult
    // TODO: 1/17/17 this is used for filtering out possible translocations that we are not currently handling, but it overkills some insertions where the inserted sequence maps to chromosomes other than that of the flanking regions
    /**
     * Determine if the two regions indicate a inter-chromosomal translocation or intra-chromosomal translocation that
     * DOES NOT involve a strand switch.
     */
    @VisibleForTesting
    static boolean isNotSimpleTranslocation(final AlignmentRegion regionWithLowerCoordOnContig,
                                            final AlignmentRegion regionWithHigherCoordOnContig,
                                            final StrandSwitch strandSwitch,
                                            final boolean involvesReferenceIntervalSwitch) {
        // logic is: must be the same reference chromosome for it not to be an inter-chromosomal translocation
        //           and when regions are mapped to the same reference chromosome, there cannot be reference position swap
        final boolean sameChromosome = regionWithLowerCoordOnContig.referenceInterval.getContig().equals(regionWithHigherCoordOnContig.referenceInterval.getContig());
        return sameChromosome
                &&
                (strandSwitch!=StrandSwitch.NO_SWITCH
                        || involvesReferenceIntervalSwitch == !regionWithLowerCoordOnContig.forwardStrand);
    }

    /**
     * An SV event could be detected from a contig that seem originate from the forward or reverse strand of the reference,
     * besides the annotation that the alignment flanking regions might flank either side of the two breakpoints.
     */
    @VisibleForTesting
    static boolean isForwardStrandRepresentation(final AlignmentRegion regionWithLowerCoordOnContig,
                                                 final AlignmentRegion regionWithHigherCoordOnContig,
                                                 final StrandSwitch strandSwitch,
                                                 final boolean involvesReferenceIntervalSwitch) {

        if (strandSwitch == StrandSwitch.NO_SWITCH) {
            return regionWithLowerCoordOnContig.forwardStrand && regionWithHigherCoordOnContig.forwardStrand;
        } else {
            return !involvesReferenceIntervalSwitch;
        }
    }

    Tuple2<SimpleInterval, SimpleInterval> getCoordSortedReferenceIntervals() {
        if (involvesRefPositionSwitch(regionWithLowerCoordOnContig, regionWithHigherCoordOnContig))
            return new Tuple2<>(regionWithHigherCoordOnContig.referenceInterval, regionWithLowerCoordOnContig.referenceInterval);
        else
            return new Tuple2<>(regionWithLowerCoordOnContig.referenceInterval, regionWithHigherCoordOnContig.referenceInterval);
    }

    protected ChimericAlignment(final Kryo kryo, final Input input) {

        this.regionWithLowerCoordOnContig = kryo.readObject(input, AlignmentRegion.class);
        this.regionWithHigherCoordOnContig = kryo.readObject(input, AlignmentRegion.class);

        this.strandSwitch = StrandSwitch.values()[input.readInt()];
        this.isForwardStrandRepresentation = input.readBoolean();

        this.contigSeq = kryo.readObject(input, byte[].class);

        @SuppressWarnings("unchecked") // trick to limit scope of unchecked cast warnings, see StackOverflow 509076
        final List<String> tempForWarning = (ArrayList<String>) kryo.readObject(input, ArrayList.class);
        this.insertionMappings = tempForWarning;
    }

    @Override
    public String toString() {
        return regionWithLowerCoordOnContig.toString() +
                "\t" +
                regionWithHigherCoordOnContig.toString() +
                "\t" +
                new String(contigSeq);
    }

    protected void serialize(final Kryo kryo, final Output output) {

        kryo.writeObject(output, regionWithLowerCoordOnContig);
        kryo.writeObject(output, regionWithHigherCoordOnContig);

        output.writeInt(strandSwitch.ordinal());
        output.writeBoolean(isForwardStrandRepresentation);

        kryo.writeObject(output, contigSeq);

        kryo.writeObject(output, insertionMappings);
    }

    public static final class Serializer extends com.esotericsoftware.kryo.Serializer<ChimericAlignment> {
        @Override
        public void write(final Kryo kryo, final Output output, final ChimericAlignment chimericAlignment) {
            chimericAlignment.serialize(kryo, output);
        }

        @Override
        public ChimericAlignment read(final Kryo kryo, final Input input, final Class<ChimericAlignment> klass ) {
            return new ChimericAlignment(kryo, input);
        }
    }
}