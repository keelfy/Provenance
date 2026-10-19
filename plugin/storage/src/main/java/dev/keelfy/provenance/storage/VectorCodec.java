package dev.keelfy.provenance.storage;

import dev.keelfy.provenance.core.model.OriginId;
import dev.keelfy.provenance.core.taint.TaintVector;
import java.util.Locale;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;
import lombok.val;

/**
 * Text form of a {@link TaintVector}: {@code origin=share;origin=share}. Origin keys never contain {@code ;} or
 * {@code =}.
 *
 * @author keelfy
 */
@UtilityClass
public class VectorCodec {

    public String encode(TaintVector vector) {
        return vector.shares().entrySet().stream()
                .map(e -> e.getKey().key() + "=" + String.format(Locale.ROOT, "%.6f", e.getValue()))
                .collect(Collectors.joining(";"));
    }

    public TaintVector decode(String text) {
        val builder = TaintVector.builder();
        if (!text.isEmpty()) {
            for (val part : text.split(";")) {
                val split = part.lastIndexOf('=');
                builder.add(new OriginId(part.substring(0, split)), Double.parseDouble(part.substring(split + 1)));
            }
        }
        return builder.build();
    }
}
