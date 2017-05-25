package org.broadinstitute.hellbender.tools.spark.sv;

import org.broadinstitute.hellbender.utils.read.GATKRead;
import java.util.function.BiPredicate;
import java.util.Iterator;

import static org.broadinstitute.hellbender.tools.spark.sv.StructuralVariationDiscoveryArgumentCollection.FindBreakpointEvidenceSparkArgumentCollection;

public class SVReadFilter implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    private final int minEvidenceMapQ;
    private final int minEvidenceMatchLength;
    private final int allowedShortFragmentOverhang;

    public SVReadFilter( final FindBreakpointEvidenceSparkArgumentCollection params ) {
        minEvidenceMapQ = params.minEvidenceMapQ;
        minEvidenceMatchLength = params.minEvidenceMatchLength;
        allowedShortFragmentOverhang = params.allowedShortFragmentOverhang;
    }

    public boolean notJunk( final GATKRead read ) {
        return !read.isDuplicate() && !read.failsVendorQualityCheck();
    }

    public boolean isPrimaryLine( final GATKRead read ) {
        return !read.isSecondaryAlignment() && !read.isSupplementaryAlignment();
    }

    public boolean isMapped( final GATKRead read ) {
        return notJunk(read) && !read.isUnmapped();
    }

    public boolean isEvidence( final GATKRead read ) {
        return isMapped(read) && read.getMappingQuality() >= minEvidenceMapQ &&
                SVUtils.matchLen(read.getCigar()) >= minEvidenceMatchLength;
    }

    public boolean isNonDiscordantEvidence( final GATKRead read ) {
        return isEvidence(read) && isPrimaryLine(read) &&
                read.isFirstOfPair() &&
                !read.mateIsUnmapped() &&
                read.isReverseStrand() != read.mateIsReverseStrand() &&
                read.getContig().equals(read.getMateContig()) &&
                (read.isReverseStrand() ?
                        read.getStart() + allowedShortFragmentOverhang >= read.getMateStart() :
                        read.getStart() - allowedShortFragmentOverhang <= read.getMateStart());
    }

    public Iterator<GATKRead> applyFilter( final Iterator<GATKRead> readItr, final BiPredicate<SVReadFilter, GATKRead> predicate ) {
        return new SVUtils.IteratorFilter<>(readItr, read -> predicate.test(this, read));
    }
}