/*
// Licensed to Julian Hyde under one or more contributor license
// agreements. See the NOTICE file distributed with this work for
// additional information regarding copyright ownership.
//
// Julian Hyde licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except in
// compliance with the License. You may obtain a copy of the License at:
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
*/
package net.hydromatic.optiq.rules.java;

import net.hydromatic.avatica.ByteString;

import net.hydromatic.linq4j.expressions.*;

import net.hydromatic.optiq.BuiltinMethod;
import net.hydromatic.optiq.DataContext;
import net.hydromatic.optiq.impl.java.JavaTypeFactory;
import net.hydromatic.optiq.runtime.SqlFunctions;

import org.eigenbase.reltype.RelDataType;
import org.eigenbase.reltype.RelDataTypeFactoryImpl;
import org.eigenbase.rex.*;
import org.eigenbase.sql.*;
import org.eigenbase.util.*;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.*;

import static org.eigenbase.sql.fun.SqlStdOperatorTable.*;

/**
 * Translates {@link org.eigenbase.rex.RexNode REX expressions} to
 * {@link Expression linq4j expressions}.
 */
public class RexToLixTranslator {
  public static final Map<Method, SqlOperator> JAVA_TO_SQL_METHOD_MAP =
      Util.<Method, SqlOperator>mapOf(
          findMethod(String.class, "toUpperCase"), UPPER,
          findMethod(
              SqlFunctions.class, "substring", String.class, Integer.TYPE,
              Integer.TYPE), SUBSTRING,
          findMethod(SqlFunctions.class, "charLength", String.class),
          CHARACTER_LENGTH,
          findMethod(SqlFunctions.class, "charLength", String.class),
          CHAR_LENGTH);

  private static final long MILLIS_IN_DAY = 24 * 60 * 60 * 1000;

  final JavaTypeFactory typeFactory;
  final RexBuilder builder;
  private final RexProgram program;
  private final RexToLixTranslator.InputGetter inputGetter;
  private final BlockBuilder list;
  private final Map<? extends RexNode, Boolean> exprNullableMap;
  private final RexToLixTranslator parent;

  private static Method findMethod(
      Class<?> clazz, String name, Class... parameterTypes) {
    try {
      return clazz.getMethod(name, parameterTypes);
    } catch (NoSuchMethodException e) {
      throw new RuntimeException(e);
    }
  }

  private RexToLixTranslator(
      RexProgram program,
      JavaTypeFactory typeFactory,
      InputGetter inputGetter,
      BlockBuilder list) {
    this(
        program, typeFactory, inputGetter, list,
        Collections.<RexNode, Boolean>emptyMap(),
        new RexBuilder(typeFactory));
  }

  private RexToLixTranslator(
      RexProgram program,
      JavaTypeFactory typeFactory,
      InputGetter inputGetter,
      BlockBuilder list,
      Map<RexNode, Boolean> exprNullableMap,
      RexBuilder builder) {
    this(program, typeFactory, inputGetter, list, exprNullableMap, builder,
        null);
  }

  private RexToLixTranslator(
      RexProgram program,
      JavaTypeFactory typeFactory,
      InputGetter inputGetter,
      BlockBuilder list,
      Map<? extends RexNode, Boolean> exprNullableMap,
      RexBuilder builder,
      RexToLixTranslator parent) {
    this.program = program;
    this.typeFactory = typeFactory;
    this.inputGetter = inputGetter;
    this.list = list;
    this.exprNullableMap = exprNullableMap;
    this.builder = builder;
    this.parent = parent;
  }

  /**
   * Translates a {@link RexProgram} to a sequence of expressions and
   * declarations.
   *
   * @param program Program to be translated
   * @param typeFactory Type factory
   * @param list List of statements, populated with declarations
   * @param outputPhysType Output type, or null
   * @param inputGetter Generates expressions for inputs
   * @return Sequence of expressions, optional condition
   */
  public static List<Expression> translateProjects(
      RexProgram program,
      JavaTypeFactory typeFactory,
      BlockBuilder list,
      PhysType outputPhysType,
      InputGetter inputGetter) {
    List<Type> storageTypes = null;
    if (outputPhysType != null) {
      final RelDataType rowType = outputPhysType.getRowType();
      storageTypes = new ArrayList<Type>(rowType.getFieldCount());
      for (int i = 0; i < rowType.getFieldCount(); i++) {
        storageTypes.add(outputPhysType.getJavaFieldType(i));
      }
    }
    return new RexToLixTranslator(program, typeFactory, inputGetter, list)
        .translateList(program.getProjectList(), storageTypes);
  }

  /** Creates a translator for translating aggregate functions. */
  public static RexToLixTranslator forAggregation(JavaTypeFactory typeFactory,
      BlockBuilder list) {
    return new RexToLixTranslator(null, typeFactory, null, list);
  }

  Expression translate(RexNode expr) {
    final RexImpTable.NullAs nullAs =
        RexImpTable.NullAs.of(isNullable(expr));
    return translate(expr, nullAs);
  }

  Expression translate(RexNode expr, RexImpTable.NullAs nullAs) {
    return translate(expr, nullAs, null);
  }

  Expression translate(RexNode expr, Type storageType) {
    final RexImpTable.NullAs nullAs =
        RexImpTable.NullAs.of(isNullable(expr));
    return translate(expr, nullAs, storageType);
  }

  Expression translate(RexNode expr, RexImpTable.NullAs nullAs,
      Type storageType) {
    Expression expression = translate0(expr, nullAs, storageType);
    assert expression != null;
    return list.append("v", expression);
  }

  Expression translateCast(
      RelDataType sourceType,
      RelDataType targetType,
      Expression operand) {
    Expression convert = null;
    switch (targetType.getSqlTypeName()) {
    case ANY:
      convert = operand;
      break;
    case BOOLEAN:
      switch (sourceType.getSqlTypeName()) {
      case CHAR:
      case VARCHAR:
        convert = Expressions.call(
            BuiltinMethod.STRING_TO_BOOLEAN.method,
            operand);
      }
      break;
    case CHAR:
    case VARCHAR:
      final SqlIntervalQualifier interval =
          sourceType.getIntervalQualifier();
      switch (sourceType.getSqlTypeName()) {
      case DATE:
        convert = RexImpTable.optimize2(
            operand,
            Expressions.call(
                BuiltinMethod.UNIX_DATE_TO_STRING.method,
                operand));
        break;
      case TIME:
        convert = RexImpTable.optimize2(
            operand,
            Expressions.call(
                BuiltinMethod.UNIX_TIME_TO_STRING.method,
                operand));
        break;
      case TIMESTAMP:
        convert = RexImpTable.optimize2(
            operand,
            Expressions.call(
                BuiltinMethod.UNIX_TIMESTAMP_TO_STRING.method,
                operand));
        break;
      case INTERVAL_YEAR_MONTH:
        convert = RexImpTable.optimize2(
            operand,
            Expressions.call(
                BuiltinMethod.INTERVAL_YEAR_MONTH_TO_STRING.method,
                operand,
                Expressions.constant(interval.foo())));
        break;
      case INTERVAL_DAY_TIME:
        convert = RexImpTable.optimize2(
            operand,
            Expressions.call(
                BuiltinMethod.INTERVAL_DAY_TIME_TO_STRING.method,
                operand,
                Expressions.constant(interval.foo()),
                Expressions.constant(interval.getFractionalSecondPrecision())));
        break;
      case BOOLEAN:
        convert = RexImpTable.optimize2(
            operand,
            Expressions.call(
                BuiltinMethod.BOOLEAN_TO_STRING.method,
                operand));
        break;
      }
    }
    if (convert == null) {
      convert = convert(operand, typeFactory.getJavaClass(targetType));
    }
    // Going from CHAR(n), trim.
    switch (sourceType.getSqlTypeName()) {
    case CHAR:
      switch (targetType.getSqlTypeName()) {
      case VARCHAR:
        convert = Expressions.call(
            BuiltinMethod.RTRIM.method, convert);
      }
      break;
    case BINARY:
      switch (targetType.getSqlTypeName()) {
      case VARBINARY:
        convert = Expressions.call(
            BuiltinMethod.RTRIM.method, convert);
      }
      break;
    }
    // Going from anything to CHAR(n) or VARCHAR(n), make sure value is no
    // longer than n.
  truncate:
    switch (targetType.getSqlTypeName()) {
    case CHAR:
    case VARCHAR:
    case BINARY:
    case VARBINARY:
      final int targetPrecision = targetType.getPrecision();
      if (targetPrecision >= 0) {
        switch (sourceType.getSqlTypeName()) {
        case CHAR:
        case VARCHAR:
        case BINARY:
        case VARBINARY:
          // If this is a widening cast, no need to truncate.
          final int sourcePrecision = sourceType.getPrecision();
          if (sourcePrecision < 0
              || sourcePrecision >= 0
              && sourcePrecision <= targetPrecision) {
            break truncate;
          }
        default:
          convert =
              Expressions.call(
                  BuiltinMethod.TRUNCATE.method,
                  convert,
                  Expressions.constant(targetPrecision));
        }
      }
      break;
    case TIMESTAMP:
      int targetScale = targetType.getScale();
      if (targetScale == RelDataType.SCALE_NOT_SPECIFIED) {
        targetScale = 0;
      }
      if (targetScale < sourceType.getScale()) {
        convert =
            Expressions.call(
                BuiltinMethod.ROUND_LONG.method,
                convert,
                Expressions.constant(
                    (long) Math.pow(10, 3 - targetScale)));
      }
      break;
    }
    return convert;
  }

  /** Translates an expression that is not in the cache.
   *
   * @param expr Expression
   * @param nullAs If false, if expression is definitely not null at
   *   runtime. Therefore we can optimize. For example, we can cast to int
   *   using x.intValue().
   * @return Translated expression
   */
  private Expression translate0(RexNode expr, RexImpTable.NullAs nullAs,
      Type storageType) {
    if (nullAs == RexImpTable.NullAs.NULL && !expr.getType().isNullable()) {
      nullAs = RexImpTable.NullAs.NOT_POSSIBLE;
    }
    switch (expr.getKind()) {
    case INPUT_REF:
      final int index = ((RexInputRef) expr).getIndex();
      Expression x = inputGetter.field(list, index, storageType);

      Expression input = list.append("inp" + index + "_", x); // safe to share
      Expression nullHandled = nullAs.handle(input);

      // If we get ConstantExpression, just return it (i.e. primitive false)
      if (nullHandled instanceof ConstantExpression) {
        return nullHandled;
      }

      // if nullHandled expression is the same as "input",
      // then we can just reuse it
      if (nullHandled == input) {
        return input;
      }

      // If nullHandled is different, then it might be unsafe to compute
      // early (i.e. unbox of null value should not happen _before_ ternary).
      // Thus we wrap it into brand-new ParameterExpression,
      // and we are guaranteed that ParameterExpression will not be shared
      String unboxVarName = "v_unboxed";
      if (input instanceof ParameterExpression) {
        unboxVarName = ((ParameterExpression) input).name + "_unboxed";
      }
      ParameterExpression unboxed = Expressions.parameter(nullHandled.getType(),
          list.newName(unboxVarName));
      list.add(Expressions.declare(0, unboxed, nullHandled));

      return unboxed;
    case LOCAL_REF:
      return translate(
          program.getExprList().get(((RexLocalRef) expr).getIndex()),
          nullAs,
          storageType);
    case LITERAL:
      return translateLiteral(
          expr,
          nullifyType(
              expr.getType(),
              isNullable(expr)
                  && nullAs != RexImpTable.NullAs.NOT_POSSIBLE),
          typeFactory,
          nullAs);
    case DYNAMIC_PARAM:
      return translateParameter((RexDynamicParam) expr, nullAs, storageType);
    default:
      if (expr instanceof RexCall) {
        return translateCall((RexCall) expr, nullAs);
      }
      throw new RuntimeException(
          "cannot translate expression " + expr);
    }
  }

  /** Translates a call to an operator or function. */
  private Expression translateCall(RexCall call, RexImpTable.NullAs nullAs) {
    final SqlOperator operator = call.getOperator();
    CallImplementor implementor =
        RexImpTable.INSTANCE.get(operator);
    if (implementor == null) {
      throw new RuntimeException("cannot translate call " + call);
    }
    return implementor.implement(this, call, nullAs);
  }

  /** Translates a parameter. */
  private Expression translateParameter(RexDynamicParam expr,
      RexImpTable.NullAs nullAs, Type storageType) {
    if (storageType == null) {
      storageType = typeFactory.getJavaClass(expr.getType());
    }
    return nullAs.handle(
        convert(
            Expressions.call(
                DataContext.ROOT,
                BuiltinMethod.DATA_CONTEXT_GET.method,
                Expressions.constant("?" + expr.getIndex())),
            storageType));
  }

  /** Translates a literal.
   *
   * @throws AlwaysNull if literal is null but {@code nullAs} is
   * {@link net.hydromatic.optiq.rules.java.RexImpTable.NullAs#NOT_POSSIBLE}.
   */
  public static Expression translateLiteral(
      RexNode expr,
      RelDataType type,
      JavaTypeFactory typeFactory,
      RexImpTable.NullAs nullAs) {
    final RexLiteral literal = (RexLiteral) expr;
    Comparable value = literal.getValue();
    if (value == null) {
      switch (nullAs) {
      case TRUE:
      case IS_NULL:
        return RexImpTable.TRUE_EXPR;
      case FALSE:
      case IS_NOT_NULL:
        return RexImpTable.FALSE_EXPR;
      case NOT_POSSIBLE:
        throw AlwaysNull.INSTANCE;
      case NULL:
        return RexImpTable.NULL_EXPR;
      }
    } else {
      switch (nullAs) {
      case IS_NOT_NULL:
        return RexImpTable.TRUE_EXPR;
      case IS_NULL:
        return RexImpTable.FALSE_EXPR;
      }
    }
    Type javaClass = typeFactory.getJavaClass(type);
    final Object value2;
    switch (literal.getType().getSqlTypeName()) {
    case DECIMAL:
      assert javaClass == BigDecimal.class;
      return value == null
          ? Expressions.constant(null)
          : Expressions.new_(
              BigDecimal.class,
              Expressions.constant(value.toString()));
    case DATE:
      value2 = value == null ? null
          : (int) (((Calendar) value).getTimeInMillis() / MILLIS_IN_DAY);
      break;
    case TIME:
      value2 = value == null ? null
          : (int) (((Calendar) value).getTimeInMillis() % MILLIS_IN_DAY);
      break;
    case TIMESTAMP:
      value2 = value == null ? null : ((Calendar) value).getTimeInMillis();
      break;
    case INTERVAL_DAY_TIME:
      if (value == null) {
        value2 = null;
        javaClass = Long.class;
      } else {
        value2 = ((BigDecimal) value).longValue();
        javaClass = long.class;
      }
      break;
    case INTERVAL_YEAR_MONTH:
      if (value == null) {
        value2 = null;
        javaClass = Integer.class;
      } else {
        value2 = ((BigDecimal) value).intValue();
        javaClass = int.class;
      }
      break;
    case CHAR:
    case VARCHAR:
      value2 = value == null ? null : ((NlsString) value).getValue();
      break;
    case BINARY:
    case VARBINARY:
      return Expressions.new_(
          ByteString.class,
          Expressions.constant(
              ((ByteString) value).getBytes(),
              byte[].class));
    case SYMBOL:
      value2 = value;
      javaClass = value.getClass();
      break;
    default:
      final Primitive primitive = Primitive.ofBoxOr(javaClass);
      if (primitive != null && value instanceof Number) {
        value2 = primitive.number((Number) value);
      } else {
        value2 = value;
      }
    }
    return Expressions.constant(value2, javaClass);
  }

  public List<Expression> translateList(
      List<RexNode> operandList,
      RexImpTable.NullAs nullAs) {
    final List<Expression> list = new ArrayList<Expression>();
    for (RexNode rex : operandList) {
      list.add(translate(rex, nullAs));
    }
    return list;
  }

  /**
   * Translates the list of {@code RexNode}, using the default output types.
   * This might be suboptimal in terms of additional box-unbox when you use
   * the translation later.
   * If you know the java class that will be used to store the results,
   * use {@link net.hydromatic.optiq.rules.java.RexToLixTranslator#translateList(java.util.List, java.util.List)}
   * version.
   *
   * @param operandList list of RexNodes to translate
   *
   * @return translated expressions
   */
  List<Expression> translateList(List<? extends RexNode> operandList) {
    return translateList(operandList, null);
  }

  /**
   * Translates the list of {@code RexNode}, while optimizing for output
   * storage.
   * For instance, if the result of translation is going to be stored in
   * {@code Object[]}, and the input is {@code Object[]} as well,
   * then translator will avoid casting, boxing, etc.
   *
   * @param operandList list of RexNodes to translate
   * @param storageTypes hints of the java classes that will be used
   *                     to store translation results. Use null to use
   *                     default storage type
   *
   * @return translated expressions
   */
  List<Expression> translateList(List<? extends RexNode> operandList,
      List<? extends Type> storageTypes) {
    final List<Expression> list = new ArrayList<Expression>(operandList.size());

    for (int i = 0; i < operandList.size(); i++) {
      RexNode rex = operandList.get(i);
      Type desiredType = null;
      if (storageTypes != null) {
        desiredType = storageTypes.get(i);
      }
      final Expression translate = translate(rex, desiredType);
      list.add(translate);
      // desiredType is still a hint, thus we might get any kind of output
      // (boxed or not) when hint was provided.
      // It is favourable to get the type matching desired type
      if (desiredType == null && !isNullable(rex)) {
        assert !Primitive.isBox(translate.getType())
            : "Not-null boxed primitive should come back as primitive: "
            + rex + ", " + translate.getType();
      }
    }
    return list;
  }

  public static Expression translateCondition(
      RexProgram program,
      JavaTypeFactory typeFactory,
      BlockBuilder list,
      InputGetter inputGetter) {
    if (program.getCondition() == null) {
      return RexImpTable.TRUE_EXPR;
    }
    final RexToLixTranslator translator =
        new RexToLixTranslator(program, typeFactory, inputGetter, list);
    return translator.translate(
        program.getCondition(),
        RexImpTable.NullAs.FALSE);
  }

  public static Expression convert(Expression operand, Type toType) {
    final Type fromType = operand.getType();
    if (fromType.equals(toType)) {
      return operand;
    }
    // E.g. from "Short" to "int".
    // Generate "x.intValue()".
    final Primitive toPrimitive = Primitive.of(toType);
    final Primitive toBox = Primitive.ofBox(toType);
    final Primitive fromBox = Primitive.ofBox(fromType);
    final Primitive fromPrimitive = Primitive.of(fromType);
    final boolean fromNumber = fromType instanceof Class
       && Number.class.isAssignableFrom((Class) fromType);
    if (fromType == String.class) {
      if (toPrimitive != null) {
        switch (toPrimitive) {
        case CHAR:
        case SHORT:
        case INT:
        case LONG:
        case FLOAT:
        case DOUBLE:
          // Generate "SqlFunctions.toShort(x)".
          return Expressions.call(
              SqlFunctions.class,
              "to" + SqlFunctions.initcap(toPrimitive.primitiveName),
              operand);
        default:
          // Generate "Short.parseShort(x)".
          return Expressions.call(
              toPrimitive.boxClass,
              "parse" + SqlFunctions.initcap(toPrimitive.primitiveName),
              operand);
        }
      }
      if (toBox != null) {
        switch (toBox) {
        case CHAR:
          // Generate "SqlFunctions.toCharBoxed(x)".
          return Expressions.call(
              SqlFunctions.class,
              "to" + SqlFunctions.initcap(toBox.primitiveName) + "Boxed",
              operand);
        default:
          // Generate "Short.valueOf(x)".
          return Expressions.call(
              toBox.boxClass,
              "valueOf",
              operand);
        }
      }
    }
    if (toPrimitive != null) {
      if (fromPrimitive != null) {
        // E.g. from "float" to "double"
        return Expressions.convert_(
            operand, toPrimitive.primitiveClass);
      }
      if (fromNumber) {
        // Generate "x.shortValue()".
        return Expressions.unbox(operand, toPrimitive);
      } else {
        // E.g. from "Object" to "short".
        // Generate "SqlFunctions.toShort(x)"
        return Expressions.call(
            SqlFunctions.class,
            "to" + SqlFunctions.initcap(toPrimitive.primitiveName),
            operand);
      }
    } else if (fromNumber && toBox != null) {
      // E.g. from "Short" to "Integer"
      // Generate "x == null ? null : Integer.valueOf(x.intValue())"
      return Expressions.condition(
          Expressions.equal(operand, RexImpTable.NULL_EXPR),
          RexImpTable.NULL_EXPR,
          Expressions.box(
              Expressions.unbox(operand, toBox),
              toBox));
    } else if (toType == BigDecimal.class) {
      if (fromBox != null) {
        // E.g. from "Integer" to "BigDecimal".
        // Generate "x == null ? null : new BigDecimal(x.intValue())"
        return Expressions.condition(
            Expressions.equal(operand, RexImpTable.NULL_EXPR),
            RexImpTable.NULL_EXPR,
            Expressions.new_(
                BigDecimal.class,
                Expressions.unbox(operand, fromBox)));
      }
      if (fromPrimitive != null) {
        // E.g. from "int" to "BigDecimal".
        // Generate "new BigDecimal(x)"
        return Expressions.new_(
            BigDecimal.class, operand);
      }
      // E.g. from "Object" to "BigDecimal".
      // Generate "x == null ? null : SqlFunctions.toBigDecimal(x)"
      return Expressions.condition(
          Expressions.equal(operand, RexImpTable.NULL_EXPR),
          RexImpTable.NULL_EXPR,
          Expressions.call(
              SqlFunctions.class,
              "toBigDecimal",
              operand));
    } else if (toType == String.class) {
      if (fromPrimitive != null) {
        switch (fromPrimitive) {
        case DOUBLE:
        case FLOAT:
          // E.g. from "double" to "String"
          // Generate "SqlFunctions.toString(x)"
          return Expressions.call(
              SqlFunctions.class,
              "toString",
              operand);
        default:
          // E.g. from "int" to "String"
          // Generate "Integer.toString(x)"
          return Expressions.call(
              fromPrimitive.boxClass,
              "toString",
              operand);
        }
      } else if (fromType == BigDecimal.class) {
        // E.g. from "BigDecimal" to "String"
        // Generate "x.toString()"
        return Expressions.condition(
            Expressions.equal(operand, RexImpTable.NULL_EXPR),
            RexImpTable.NULL_EXPR,
            Expressions.call(
                SqlFunctions.class,
                "toString",
                operand));
      } else {
        // E.g. from "BigDecimal" to "String"
        // Generate "x == null ? null : x.toString()"
        return Expressions.condition(
            Expressions.equal(operand, RexImpTable.NULL_EXPR),
            RexImpTable.NULL_EXPR,
            Expressions.call(
                operand,
                "toString"));
      }
    }
    return Expressions.convert_(operand, toType);
  }

  private static <T> T elvis(T t0, T t1) {
    return t0 != null ? t0 : t1;
  }

  private static <T> T elvis(T t0, T t1, T t2) {
    return t0 != null ? t0 : t1 != null ? t1 : t2;
  }

  public Expression translateConstructor(
      List<RexNode> operandList, SqlKind kind) {
    switch (kind) {
    case MAP_VALUE_CONSTRUCTOR:
      Expression map =
          list.append(
              "map",
              Expressions.new_(LinkedHashMap.class),
              false);
      for (int i = 0; i < operandList.size(); i++) {
        RexNode key = operandList.get(i++);
        RexNode value = operandList.get(i);
        list.add(
            Expressions.statement(
                Expressions.call(
                    map,
                    BuiltinMethod.MAP_PUT.method,
                    Expressions.box(translate(key)),
                    Expressions.box(translate(value)))));
      }
      return map;
    case ARRAY_VALUE_CONSTRUCTOR:
      Expression lyst =
          list.append(
              "list",
              Expressions.new_(ArrayList.class),
              false);
      for (RexNode value : operandList) {
        list.add(
            Expressions.statement(
                Expressions.call(
                    lyst,
                    BuiltinMethod.COLLECTION_ADD.method,
                    Expressions.box(translate(value)))));
      }
      return lyst;
    default:
      throw new AssertionError("unexpected: " + kind);
    }
  }

  /** Returns whether an expression is nullable. Even if its type says it is
   * nullable, if we have previously generated a check to make sure that it is
   * not null, we will say so.
   *
   * <p>For example, {@code WHERE a == b} translates to
   * {@code a != null && b != null && a.equals(b)}. When translating the
   * 3rd part of the disjunction, we already know a and b are not null.</p>
   *
   * @param e Expression
   * @return Whether expression is nullable in the current translation context
   */
  public boolean isNullable(RexNode e) {
    if (!e.getType().isNullable()) {
      return false;
    }
    final Boolean b = isKnownNullable(e);
    return b == null || b;
  }

  /**
   * Walks parent translator chain and verifies if the expression is nullable.
   *
   * @param node RexNode to check if it is nullable or not
   * @return null when nullability is not known, true or false otherwise
   */
  protected Boolean isKnownNullable(RexNode node) {
    if (!exprNullableMap.isEmpty()) {
      Boolean nullable = exprNullableMap.get(node);
      if (nullable != null) {
        return nullable;
      }
    }
    return parent == null ? null : parent.isKnownNullable(node);
  }

  /** Creates a read-only copy of this translator that records that a given
   * expression is nullable. */
  public RexToLixTranslator setNullable(RexNode e, boolean nullable) {
    return setNullable(Collections.singletonMap(e, nullable));
  }

  /** Creates a read-only copy of this translator that records that a given
   * expression is nullable. */
  public RexToLixTranslator setNullable(Map<? extends RexNode,
                                        Boolean> nullable) {
    return new RexToLixTranslator(
        program, typeFactory, inputGetter, list, nullable, builder, this);
  }

  public RelDataType nullifyType(RelDataType type, boolean nullable) {
    if (!nullable) {
      final Primitive primitive = javaPrimitive(type);
      if (primitive != null) {
        return typeFactory.createJavaType(primitive.primitiveClass);
      }
    }
    return typeFactory.createTypeWithNullability(type, nullable);
  }

  private Primitive javaPrimitive(RelDataType type) {
    if (type instanceof RelDataTypeFactoryImpl.JavaType) {
      return Primitive.ofBox(
          ((RelDataTypeFactoryImpl.JavaType) type).getJavaClass());
    }
    return null;
  }

  /** Translates a field of an input to an expression. */
  public interface InputGetter {
    Expression field(BlockBuilder list, int index, Type storageType);
  }

  /** Implementation of {@link InputGetter} that calls
   * {@link PhysType#fieldReference}. */
  public static class InputGetterImpl implements InputGetter {
    private List<Pair<Expression, PhysType>> inputs;

    public InputGetterImpl(List<Pair<Expression, PhysType>> inputs) {
      this.inputs = inputs;
    }

    public Expression field(BlockBuilder list, int index, Type storageType) {
      final Pair<Expression, PhysType> input = inputs.get(0);
      final PhysType physType = input.right;
      final Expression left = list.append("current", input.left);
      return physType.fieldReference(left, index, storageType);
    }
  }

  /** Thrown in the unusual (but not erroneous) situation where the expression
   * we are translating is the null literal but we have already checked that
   * it is not null. It is easier to throw (and caller will always handle)
   * than to check exhaustively beforehand. */
  static class AlwaysNull extends ControlFlowException {
    public static final AlwaysNull INSTANCE = new AlwaysNull();

    private AlwaysNull() {}
  }
}

// End RexToLixTranslator.java
