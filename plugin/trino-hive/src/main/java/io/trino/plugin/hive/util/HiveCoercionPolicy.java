/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.hive.util;

import io.trino.metastore.HiveType;
import io.trino.metastore.type.Category;
import io.trino.metastore.type.ListTypeInfo;
import io.trino.metastore.type.MapTypeInfo;
import io.trino.metastore.type.StructTypeInfo;
import io.trino.metastore.type.TypeInfoFactory;
import io.trino.plugin.hive.HiveTimestampPrecision;
import io.trino.spi.type.CharType;
import io.trino.spi.type.DecimalType;
import io.trino.spi.type.Type;
import io.trino.spi.type.TypeManager;
import io.trino.spi.type.VarbinaryType;
import io.trino.spi.type.VarcharType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static io.trino.metastore.HiveType.HIVE_BOOLEAN;
import static io.trino.metastore.HiveType.HIVE_BYTE;
import static io.trino.metastore.HiveType.HIVE_DATE;
import static io.trino.metastore.HiveType.HIVE_DOUBLE;
import static io.trino.metastore.HiveType.HIVE_FLOAT;
import static io.trino.metastore.HiveType.HIVE_INT;
import static io.trino.metastore.HiveType.HIVE_LONG;
import static io.trino.metastore.HiveType.HIVE_SHORT;
import static io.trino.metastore.HiveType.HIVE_TIMESTAMP;
import static io.trino.plugin.hive.util.HiveTypeTranslator.toHiveType;
import static io.trino.plugin.hive.util.HiveTypeUtil.getType;
import static io.trino.plugin.hive.util.HiveTypeUtil.getTypeSignature;
import static io.trino.plugin.hive.util.HiveUtil.extractStructFieldTypes;
import static java.util.Objects.requireNonNull;

public final class HiveCoercionPolicy
{
    private final TypeManager typeManager;

    private HiveCoercionPolicy(TypeManager typeManager)
    {
        this.typeManager = requireNonNull(typeManager, "typeManager is null");
    }

    public static boolean canCoerce(TypeManager typeManager, HiveType fromHiveType, HiveType toHiveType, HiveTimestampPrecision hiveTimestampPrecision)
    {
        return new HiveCoercionPolicy(typeManager).canCoerce(fromHiveType, toHiveType, hiveTimestampPrecision);
    }

    /**
     * If coercion is not possible due to one or more invalid primitive type change: return an empty Option
     * Otherwise return a composite schema that has:
     * - toHiveType structure (for all nested fields at all depths: map, array, structs) ordering included
     * - fromHiveType primitive types (aka: leaf types)
     *
     * The returned HiveType is aimed to be passed to the file format reader:
     * - so the file reader can read the file with it's correct primitive types
     * - be coercible to the toType thanks to leaf type coercions only
     */
    public static Optional<HiveType> coercibleIntermediate(TypeManager typeManager, HiveType fromHiveType, HiveType toHiveType, HiveTimestampPrecision hiveTimestampPrecision)
    {
        return new HiveCoercionPolicy(typeManager).coercibleIntermediate(fromHiveType, toHiveType, hiveTimestampPrecision);
    }

    private boolean canCoerce(HiveType fromHiveType, HiveType toHiveType, HiveTimestampPrecision hiveTimestampPrecision)
    {
        return canCoerceForPrimitive(fromHiveType, toHiveType, hiveTimestampPrecision)
                || canCoerceForList(fromHiveType, toHiveType, hiveTimestampPrecision)
                || canCoerceForMap(fromHiveType, toHiveType, hiveTimestampPrecision)
                || canCoerceForStructOrUnion(fromHiveType, toHiveType, hiveTimestampPrecision);
    }

    private boolean canCoerceForPrimitive(HiveType fromHiveType, HiveType toHiveType, HiveTimestampPrecision hiveTimestampPrecision)
    {
        Type fromType = typeManager.getType(getTypeSignature(fromHiveType, hiveTimestampPrecision));
        Type toType = typeManager.getType(getTypeSignature(toHiveType, hiveTimestampPrecision));
        if (fromType instanceof VarcharType) {
            return toType instanceof VarcharType ||
                    toType instanceof CharType ||
                    toHiveType.equals(HIVE_BOOLEAN) ||
                    toHiveType.equals(HIVE_BYTE) ||
                    toHiveType.equals(HIVE_SHORT) ||
                    toHiveType.equals(HIVE_INT) ||
                    toHiveType.equals(HIVE_LONG) ||
                    toHiveType.equals(HIVE_FLOAT) ||
                    toHiveType.equals(HIVE_DOUBLE) ||
                    toHiveType.equals(HIVE_DATE) ||
                    toHiveType.equals(HIVE_TIMESTAMP);
        }
        if (fromType instanceof CharType) {
            return toType instanceof CharType ||
                    toType instanceof VarcharType;
        }
        if (toType instanceof VarcharType) {
            return fromHiveType.equals(HIVE_BOOLEAN) ||
                    fromHiveType.equals(HIVE_BYTE) ||
                    fromHiveType.equals(HIVE_SHORT) ||
                    fromHiveType.equals(HIVE_INT) ||
                    fromHiveType.equals(HIVE_LONG) ||
                    fromHiveType.equals(HIVE_TIMESTAMP) ||
                    fromHiveType.equals(HIVE_FLOAT) ||
                    fromHiveType.equals(HIVE_DOUBLE) ||
                    fromHiveType.equals(HIVE_DATE) ||
                    fromType instanceof DecimalType ||
                    fromType instanceof VarbinaryType;
        }
        if (toHiveType.equals(HIVE_DATE)) {
            return fromHiveType.equals(HIVE_TIMESTAMP);
        }
        if (fromHiveType.equals(HIVE_BYTE)) {
            return toHiveType.equals(HIVE_SHORT) || toHiveType.equals(HIVE_INT) || toHiveType.equals(HIVE_LONG) || toHiveType.equals(HIVE_DOUBLE) || toType instanceof DecimalType;
        }
        if (fromHiveType.equals(HIVE_SHORT)) {
            return toHiveType.equals(HIVE_INT) || toHiveType.equals(HIVE_LONG) || toHiveType.equals(HIVE_DOUBLE) || toType instanceof DecimalType;
        }
        if (fromHiveType.equals(HIVE_INT)) {
            return toHiveType.equals(HIVE_LONG) || toHiveType.equals(HIVE_DOUBLE) || toType instanceof DecimalType;
        }
        if (fromHiveType.equals(HIVE_LONG)) {
            return toHiveType.equals(HIVE_DOUBLE) || toType instanceof DecimalType;
        }
        if (fromHiveType.equals(HIVE_FLOAT)) {
            return toHiveType.equals(HIVE_DOUBLE) || toType instanceof DecimalType;
        }
        if (fromHiveType.equals(HIVE_DOUBLE)) {
            return toHiveType.equals(HIVE_FLOAT) || toType instanceof DecimalType;
        }
        if (fromType instanceof DecimalType) {
            return toType instanceof DecimalType ||
                    toHiveType.equals(HIVE_FLOAT) ||
                    toHiveType.equals(HIVE_DOUBLE) ||
                    toHiveType.equals(HIVE_BYTE) ||
                    toHiveType.equals(HIVE_SHORT) ||
                    toHiveType.equals(HIVE_INT) ||
                    toHiveType.equals(HIVE_LONG);
        }

        return false;
    }

    private boolean canCoerceForMap(HiveType fromHiveType, HiveType toHiveType, HiveTimestampPrecision hiveTimestampPrecision)
    {
        if (fromHiveType.getCategory() != Category.MAP || toHiveType.getCategory() != Category.MAP) {
            return false;
        }
        HiveType fromKeyType = HiveType.valueOf(((MapTypeInfo) fromHiveType.getTypeInfo()).getMapKeyTypeInfo().getTypeName());
        HiveType fromValueType = HiveType.valueOf(((MapTypeInfo) fromHiveType.getTypeInfo()).getMapValueTypeInfo().getTypeName());
        HiveType toKeyType = HiveType.valueOf(((MapTypeInfo) toHiveType.getTypeInfo()).getMapKeyTypeInfo().getTypeName());
        HiveType toValueType = HiveType.valueOf(((MapTypeInfo) toHiveType.getTypeInfo()).getMapValueTypeInfo().getTypeName());
        return (fromKeyType.equals(toKeyType) || canCoerce(fromKeyType, toKeyType, hiveTimestampPrecision)) &&
                (fromValueType.equals(toValueType) || canCoerce(fromValueType, toValueType, hiveTimestampPrecision));
    }

    private boolean canCoerceForList(HiveType fromHiveType, HiveType toHiveType, HiveTimestampPrecision hiveTimestampPrecision)
    {
        if (fromHiveType.getCategory() != Category.LIST || toHiveType.getCategory() != Category.LIST) {
            return false;
        }
        HiveType fromElementType = HiveType.valueOf(((ListTypeInfo) fromHiveType.getTypeInfo()).getListElementTypeInfo().getTypeName());
        HiveType toElementType = HiveType.valueOf(((ListTypeInfo) toHiveType.getTypeInfo()).getListElementTypeInfo().getTypeName());
        return fromElementType.equals(toElementType) || canCoerce(fromElementType, toElementType, hiveTimestampPrecision);
    }

    private boolean canCoerceForStructOrUnion(HiveType fromHiveType, HiveType toHiveType, HiveTimestampPrecision hiveTimestampPrecision)
    {
        if (!isStructOrUnion(fromHiveType) || !isStructOrUnion(toHiveType)) {
            return false;
        }
        HiveType fromHiveTypeStruct = (fromHiveType.getCategory() == Category.UNION) ? convertUnionToStruct(fromHiveType, typeManager, hiveTimestampPrecision) : fromHiveType;
        HiveType toHiveTypeStruct = (toHiveType.getCategory() == Category.UNION) ? convertUnionToStruct(toHiveType, typeManager, hiveTimestampPrecision) : toHiveType;

        List<String> fromFieldNames = ((StructTypeInfo) fromHiveTypeStruct.getTypeInfo()).getAllStructFieldNames();
        List<String> toFieldNames = ((StructTypeInfo) toHiveTypeStruct.getTypeInfo()).getAllStructFieldNames();
        List<HiveType> fromFieldTypes = extractStructFieldTypes(fromHiveTypeStruct);
        List<HiveType> toFieldTypes = extractStructFieldTypes(toHiveTypeStruct);
        // Rule:
        // * Fields may be added or dropped.
        // * A field with a given name must be of the same type or coercible.
        for (int toIdx = 0; toIdx < toFieldTypes.size(); toIdx++) {
            int fromIdx = indexOfIgnoreCase(fromFieldNames, toFieldNames.get(toIdx));
            if (fromIdx >= 0) {
                HiveType fromType = fromFieldTypes.get(fromIdx);
                HiveType toType = toFieldTypes.get(toIdx);
                if (!fromType.equals(toType) && !canCoerce(fromType, toType, hiveTimestampPrecision)) {
                    return false;
                }
            }
        }
        return true;
    }

    private Optional<HiveType> coercibleIntermediate(HiveType fromHiveType, HiveType toHiveType, HiveTimestampPrecision hiveTimestampPrecision)
    {
        if (fromHiveType.equals(toHiveType) || canCoerceForPrimitive(fromHiveType, toHiveType, hiveTimestampPrecision)) {
            return Optional.of(fromHiveType);
        }

        Optional<HiveType> listOpt = coercibleIntermediateForList(fromHiveType, toHiveType, hiveTimestampPrecision);
        if (listOpt.isPresent()) {
            return listOpt;
        }

        Optional<HiveType> structOpt = coercibleIntermediateForStructOrUnion(fromHiveType, toHiveType, hiveTimestampPrecision);
        if (structOpt.isPresent()) {
            return structOpt;
        }

        Optional<HiveType> mapOpt = coercibleIntermediateForMap(fromHiveType, toHiveType, hiveTimestampPrecision);
        if (mapOpt.isPresent()) {
            return mapOpt;
        }

        return Optional.empty();
    }

    private Optional<HiveType> coercibleIntermediateForMap(HiveType fromHiveType, HiveType toHiveType, HiveTimestampPrecision hiveTimestampPrecision)
    {
        if (fromHiveType.getCategory() != Category.MAP || toHiveType.getCategory() != Category.MAP) {
            return Optional.empty();
        }

        MapTypeInfo fromMap = (MapTypeInfo) fromHiveType.getTypeInfo();
        MapTypeInfo toMap = (MapTypeInfo) toHiveType.getTypeInfo();

        HiveType fromKey = HiveType.valueOf(fromMap.getMapKeyTypeInfo().getTypeName());
        HiveType fromValue = HiveType.valueOf(fromMap.getMapValueTypeInfo().getTypeName());

        HiveType toKey = HiveType.valueOf(toMap.getMapKeyTypeInfo().getTypeName());
        HiveType toValue = HiveType.valueOf(toMap.getMapValueTypeInfo().getTypeName());

        Optional<HiveType> keyType =
                fromKey.equals(toKey)
                        ? Optional.of(fromKey)
                        : coercibleIntermediate(fromKey, toKey, hiveTimestampPrecision);

        if (keyType.isEmpty()) {
            return Optional.empty();
        }

        Optional<HiveType> valueType =
                fromValue.equals(toValue)
                        ? Optional.of(fromValue)
                        : coercibleIntermediate(fromValue, toValue, hiveTimestampPrecision);

        if (valueType.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(HiveType.fromTypeInfo(
                TypeInfoFactory.getMapTypeInfo(
                        keyType.get().getTypeInfo(),
                        valueType.get().getTypeInfo())));
    }

    private Optional<HiveType> coercibleIntermediateForList(HiveType fromHiveType, HiveType toHiveType, HiveTimestampPrecision hiveTimestampPrecision)
    {
        if (fromHiveType.getCategory() != Category.LIST || toHiveType.getCategory() != Category.LIST) {
            return Optional.empty();
        }

        HiveType fromElementType = HiveType.valueOf(((ListTypeInfo) fromHiveType.getTypeInfo()).getListElementTypeInfo().getTypeName());
        HiveType toElementType = HiveType.valueOf(((ListTypeInfo) toHiveType.getTypeInfo()).getListElementTypeInfo().getTypeName());

        if (fromElementType.equals(toElementType)) {
            return Optional.of(fromHiveType);
        }
        return coercibleIntermediate(fromElementType, toElementType, hiveTimestampPrecision)
                .map(elementTypeMiddleGround ->
                        HiveType.fromTypeInfo(
                                TypeInfoFactory.getListTypeInfo(elementTypeMiddleGround.getTypeInfo())));
    }

    private Optional<HiveType> coercibleIntermediateForStructOrUnion(HiveType fromHiveType, HiveType toHiveType, HiveTimestampPrecision hiveTimestampPrecision)
    {
        if (!isStructOrUnion(fromHiveType) || !isStructOrUnion(toHiveType)) {
            return Optional.empty();
        }

        HiveType fromHiveTypeStruct = (fromHiveType.getCategory() == Category.UNION)
                ? convertUnionToStruct(fromHiveType, typeManager, hiveTimestampPrecision)
                : fromHiveType;
        HiveType toHiveTypeStruct = (toHiveType.getCategory() == Category.UNION)
                ? convertUnionToStruct(toHiveType, typeManager, hiveTimestampPrecision)
                : toHiveType;

        List<String> fromFieldNames = ((StructTypeInfo) fromHiveTypeStruct.getTypeInfo()).getAllStructFieldNames();
        StructTypeInfo toStructTypeInfo = (StructTypeInfo) toHiveTypeStruct.getTypeInfo();
        List<String> toFieldNames = toStructTypeInfo.getAllStructFieldNames();
        List<HiveType> fromFieldTypes = extractStructFieldTypes(fromHiveTypeStruct);
        List<HiveType> toFieldTypes = extractStructFieldTypes(toHiveTypeStruct);

        List<String> fieldDefinitions = new ArrayList<>(toFieldTypes.size());

        // Rule:
        // * Fields may be added or dropped.
        // * A field with a given name must be of the same type or coercible.
        for (int toIdx = 0; toIdx < toFieldTypes.size(); toIdx++) {
            String toFieldName = toFieldNames.get(toIdx);
            HiveType resultingType = toFieldTypes.get(toIdx);

            int fromIdx = indexOfIgnoreCase(fromFieldNames, toFieldName);
            if (fromIdx >= 0) {
                HiveType fromType = fromFieldTypes.get(fromIdx);
                HiveType toType = toFieldTypes.get(toIdx);

                Optional<HiveType> fieldMiddleGround = coercibleIntermediate(fromType, toType, hiveTimestampPrecision);
                if (fieldMiddleGround.isEmpty()) {
                    return Optional.empty();
                }
                resultingType = fieldMiddleGround.get();
            }

            fieldDefinitions.add(toFieldName + ":" + resultingType.getTypeInfo().getTypeName());
        }

        return Optional.of(HiveType.valueOf("struct<" + String.join(",", fieldDefinitions) + ">"));
    }

    private static boolean isStructOrUnion(HiveType hiveType)
    {
        return (hiveType.getCategory() == Category.STRUCT) || (hiveType.getCategory() == Category.UNION);
    }

    private static HiveType convertUnionToStruct(HiveType unionType, TypeManager typeManager, HiveTimestampPrecision hiveTimestampPrecision)
    {
        checkArgument(unionType.getCategory() == Category.UNION, "Can only convert union type to struct type, given type: %s", getTypeSignature(unionType, hiveTimestampPrecision));
        return toHiveType(getType(unionType, typeManager, hiveTimestampPrecision));
    }

    private static int indexOfIgnoreCase(List<String> fieldNames, String fieldName)
    {
        String target = fieldName.toLowerCase(Locale.ENGLISH);
        for (int i = 0; i < fieldNames.size(); i++) {
            if (fieldNames.get(i).toLowerCase(Locale.ENGLISH).equals(target)) {
                return i;
            }
        }
        return -1;
    }
}
