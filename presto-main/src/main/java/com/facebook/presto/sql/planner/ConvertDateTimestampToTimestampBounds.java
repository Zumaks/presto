package com.facebook.presto.sql.planner;

import com.facebook.presto.common.function.OperatorType;
import com.facebook.presto.common.type.DateType;
import com.facebook.presto.common.type.TimestampType;
import com.facebook.presto.hive.$internal.com.google.common.collect.ImmutableList;
import com.facebook.presto.matching.Captures;
import com.facebook.presto.matching.Pattern;
import com.facebook.presto.metadata.CastType;
import com.facebook.presto.metadata.FunctionAndTypeManager;
import com.facebook.presto.spi.function.FunctionHandle;
import com.facebook.presto.spi.function.StandardFunctionResolution;
import com.facebook.presto.spi.plan.FilterNode;
import com.facebook.presto.spi.relation.CallExpression;
import com.facebook.presto.spi.relation.ConstantExpression;
import com.facebook.presto.spi.relation.RowExpression;
import com.facebook.presto.spi.relation.SpecialFormExpression;
import com.facebook.presto.sql.analyzer.FunctionAndTypeResolver;
import com.facebook.presto.sql.planner.iterative.Rule;
import com.facebook.presto.sql.relational.FunctionResolution;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

import static com.facebook.presto.common.function.OperatorType.GREATER_THAN_OR_EQUAL;
import static com.facebook.presto.common.function.OperatorType.LESS_THAN;
import static com.facebook.presto.common.type.BooleanType.BOOLEAN;
import static com.facebook.presto.matching.Pattern.typeOf;
import static com.facebook.presto.spi.relation.SpecialFormExpression.Form.AND;
import static com.facebook.presto.sql.relational.Expressions.comparisonExpression;
import static java.util.Objects.requireNonNull;

public class ConvertDateTimestampToTimestampBounds
        implements Rule<FilterNode>
{
    private static final Pattern<FilterNode> PATTERN = typeOf(FilterNode.class);
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final FunctionAndTypeManager functionAndTypeManager;
    private final StandardFunctionResolution functionResolution;

    public ConvertDateTimestampToTimestampBounds(FunctionAndTypeManager functionAndTypeManager)
    {
        this.functionAndTypeManager = requireNonNull(functionAndTypeManager, "functionAndTypeManager is null");
        this.functionResolution = new FunctionResolution((FunctionAndTypeResolver) functionAndTypeManager);
    }

    @Override
    public Pattern<FilterNode> getPattern()
    {
        return PATTERN;
    }

    @Override
    public Result apply(FilterNode node, Captures captures, Context context)
    {
        RowExpression predicate = node.getPredicate();
        if (!(predicate instanceof CallExpression)) {
            return Result.empty();
        }

        CallExpression equality = (CallExpression) predicate;
        if (!functionResolution.isEqualsFunction(equality.getFunctionHandle())
                || equality.getArguments().size() != 2) {
            return Result.empty();
        }

        RowExpression left  = unwrapCasts(equality.getArguments().get(0));
        RowExpression right = unwrapCasts(equality.getArguments().get(1));

        /* ---------- 1. date(...) = DATE 'lit' ------------------------------- */
        Optional<RowExpression> rewritten = tryRewriteDateEquality(left, right);
        if (!rewritten.isPresent()) {
            rewritten = tryRewriteDateEquality(right, left);
        }

        /* ---------- 2. year/month/hour/date_trunc --------------------------- */
        if (!rewritten.isPresent()) {
            rewritten = Optional.ofNullable(rewriteExtendedEquality(left, right));
        }

        return rewritten.map(rowExpression -> Result.ofPlanNode(
                new FilterNode(
                        node.getSourceLocation(),
                        node.getId(),
                        node.getSource(),
                        rowExpression))).orElseGet(Result::empty);

    }

    /* ====================================================================== */
    /* ===                           date()                                === */
    /* ====================================================================== */

    private Optional<RowExpression> tryRewriteDateEquality(RowExpression functionSide, RowExpression literalSide)
    {
        if (!(functionSide instanceof CallExpression)) {
            return Optional.empty();
        }
        CallExpression dateCall = (CallExpression) functionSide;
        if (!"date".equalsIgnoreCase(dateCall.getDisplayName()) || dateCall.getArguments().size() != 1) {
            return Optional.empty();
        }

        RowExpression tsExpr = unwrapCasts(dateCall.getArguments().get(0));
        if (!(tsExpr.getType() instanceof TimestampType)) {
            return Optional.empty();
        }

        RowExpression maybeDateLiteral = unwrapDateLiteralIfConstant(literalSide);
        if (!(maybeDateLiteral instanceof ConstantExpression)
                || !(maybeDateLiteral.getType() instanceof DateType)) {
            return Optional.empty();
        }

        return rewriteDateRange(tsExpr, (ConstantExpression) maybeDateLiteral);
    }

    private RowExpression unwrapDateLiteralIfConstant(RowExpression expr)
    {
        /* Already a DATE constant */
        if (expr instanceof ConstantExpression && expr.getType() instanceof DateType) {
            return expr;
        }

        /* CAST('yyyy-MM-dd' AS date) */
        if (expr instanceof CallExpression) {
            CallExpression cast = (CallExpression) expr;
            if (functionResolution.isCastFunction(cast.getFunctionHandle())
                    && cast.getArguments().size() == 1
                    && cast.getType() instanceof DateType
                    && cast.getArguments().get(0) instanceof ConstantExpression) {
                ConstantExpression arg = (ConstantExpression) cast.getArguments().get(0);
                String text = arg.getValue().toString();
                try {
                    LocalDate ld = LocalDate.parse(text);
                    long epochDay = ld.toEpochDay();
                    return new ConstantExpression(expr.getSourceLocation(), epochDay, DateType.DATE);
                }
                catch (DateTimeParseException ignored) {
                }
            }
        }
        return expr;
    }

    private Optional<RowExpression> rewriteDateRange(RowExpression tsExpr, ConstantExpression dateLiteral)
    {
        long epochDay = ((Number) dateLiteral.getValue()).longValue();

        ConstantExpression lowerDate = new ConstantExpression(dateLiteral.getSourceLocation(), epochDay,     DateType.DATE);
        ConstantExpression upperDate = new ConstantExpression(dateLiteral.getSourceLocation(), epochDay + 1, DateType.DATE);

        CallExpression lowerTs = buildDateToTimestampCast(lowerDate);
        CallExpression upperTs = buildDateToTimestampCast(upperDate);

        RowExpression ge = comparisonExpression(functionResolution, GREATER_THAN_OR_EQUAL, tsExpr, lowerTs);
        RowExpression lt = comparisonExpression(functionResolution, LESS_THAN,            tsExpr, upperTs);

        return Optional.of(createAndExpression(ge, lt));
    }

    private CallExpression buildDateToTimestampCast(ConstantExpression dateConstant)
    {
        FunctionHandle cast = functionAndTypeManager.lookupCast(
                CastType.CAST,
                dateConstant.getType(),
                TimestampType.TIMESTAMP);

        return new CallExpression(
                dateConstant.getSourceLocation(),
                OperatorType.CAST.name(),
                cast,
                TimestampType.TIMESTAMP,
                ImmutableList.of(dateConstant));
    }

    /* ====================================================================== */
    /* ===            year() / month() / hour() / date_trunc()             === */
    /* ====================================================================== */

    private RowExpression rewriteExtendedEquality(RowExpression left, RowExpression right)
    {
        /* ---------- year() ---------- */
        Optional<RowExpression> yearCol = extractFunctionArgument("year", left, right);
        Optional<ConstantExpression> yearLit = extractNumericLiteral(left, right);
        if (yearCol.isPresent() && yearLit.isPresent()) {
            long y = ((Number) yearLit.get().getValue()).longValue();
            String lower = String.format("%d-01-01 00:00:00.000", y);
            String upper = String.format("%d-01-01 00:00:00.000", y + 1);
            return createAndExpression(
                    comparisonExpression(functionResolution, GREATER_THAN_OR_EQUAL, yearCol.get(), timestampLiteral(lower)),
                    comparisonExpression(functionResolution, LESS_THAN,            yearCol.get(), timestampLiteral(upper)));
        }

        /* ---------- month() ---------- */
        Optional<RowExpression> monthCol = extractFunctionArgument("month", left, right);
        Optional<ConstantExpression> monthLit = extractStringLiteral(left, right);
        if (monthCol.isPresent() && monthLit.isPresent()) {
            String[] p = monthLit.get().getValue().toString().split("-");
            if (p.length == 2) {
                int yy = Integer.parseInt(p[0]);
                int mm = Integer.parseInt(p[1]);
                String lower = String.format("%d-%02d-01 00:00:00.000", yy, mm);
                String upper = (mm == 12)
                        ? String.format("%d-01-01 00:00:00.000", yy + 1)
                        : String.format("%d-%02d-01 00:00:00.000", yy, mm + 1);
                return createAndExpression(
                        comparisonExpression(functionResolution, GREATER_THAN_OR_EQUAL, monthCol.get(), timestampLiteral(lower)),
                        comparisonExpression(functionResolution, LESS_THAN,            monthCol.get(), timestampLiteral(upper)));
            }
        }

        /* ---------- hour() ---------- */
        Optional<RowExpression> hourCol  = extractFunctionArgument("hour", left, right);
        Optional<ConstantExpression> hourLit = extractStringLiteral(left, right);

        if (hourCol.isPresent() && hourLit.isPresent()) {
            // Expected literal format:  YYYY-MM-DD-HH
            String hourToken = hourLit.get().getValue().toString();
            String[] parts = hourToken.split("-");
            if (parts.length == 4) {
                try {
                    int yy = Integer.parseInt(parts[0]);
                    int mm = Integer.parseInt(parts[1]);
                    int dd = Integer.parseInt(parts[2]);
                    int hh = Integer.parseInt(parts[3]);

            /* build lower bound and compute upper via LocalDateTime math
               so 2020-05-31-23 rolls over to 2020-06-01-00 correctly       */
                    LocalDateTime lowerDt = LocalDateTime.of(yy, mm, dd, hh, 0, 0, 0);
                    String  lower = lowerDt.format(TS_FMT);
                    String  upper = lowerDt.plusHours(1).format(TS_FMT);

                    return createAndExpression(
                            comparisonExpression(functionResolution,
                                    GREATER_THAN_OR_EQUAL,
                                    hourCol.get(),
                                    timestampLiteral(lower)),
                            comparisonExpression(functionResolution,
                                    LESS_THAN,
                                    hourCol.get(),
                                    timestampLiteral(upper)));
                }
                catch (NumberFormatException | java.time.DateTimeException ignore) {
                    /* fall through – let the original predicate stand */
                }
            }
        }

        /* ---------- date_trunc() ---------- */
        Optional<CallExpression> dtCallOpt = extractDateTruncCall(left);
        if (!dtCallOpt.isPresent()) {
            dtCallOpt = extractDateTruncCall(right);
        }
        Optional<ConstantExpression> tsLit = extractTimestampLiteral(left, right);
        if (dtCallOpt.isPresent() && tsLit.isPresent()) {
            CallExpression dtCall = dtCallOpt.get();
            ConstantExpression unitExpr = (ConstantExpression) dtCall.getArguments().get(0);
            String unit = unitExpr.getValue().toString().toLowerCase();
            RowExpression tsColumn = dtCall.getArguments().get(1);

            String baseStr = tsLit.get().getValue().toString();
            LocalDateTime base = LocalDateTime.parse(baseStr, TS_FMT);
            LocalDateTime next;
            switch (unit) {
                case "day":
                    next = base.plusDays(1);
                    break;
                case "hour":
                    next = base.plusHours(1);
                    break;
                case "month":
                    next = base.plusMonths(1);
                    break;
                case "year":
                    next = base.plusYears(1);
                    break;
                default:
                    return null;   // unsupported
            }

            return createAndExpression(
                    comparisonExpression(functionResolution, GREATER_THAN_OR_EQUAL, tsColumn, timestampLiteral(baseStr)),
                    comparisonExpression(functionResolution, LESS_THAN,            tsColumn, timestampLiteral(next.format(TS_FMT))));
        }

        /* nothing matched */
        return null;
    }

    /* ====================================================================== */
    /* ===                       Helper utilities                          === */
    /* ====================================================================== */

    private RowExpression createAndExpression(RowExpression left, RowExpression right)
    {
        return new SpecialFormExpression(
                left.getSourceLocation(),
                AND,
                BOOLEAN,
                ImmutableList.of(left, right));
    }

    private ConstantExpression timestampLiteral(String text)
    {
        LocalDateTime ldt = LocalDateTime.parse(text, TS_FMT);
        long epochMillis  = ldt.atZone(java.time.ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli();
        return new ConstantExpression(
                null ,
                epochMillis,
                TimestampType.TIMESTAMP);
    }

    private RowExpression unwrapCasts(RowExpression expr)
    {
        while (expr instanceof CallExpression) {
            CallExpression call = (CallExpression) expr;
            if (functionResolution.isCastFunction(call.getFunctionHandle())
                    && call.getArguments().size() == 1) {
                RowExpression inner = call.getArguments().get(0);
                if (call.getType().equals(inner.getType())) {
                    expr = inner;
                    continue;
                }
            }
            break;
        }
        return expr;
    }

    private Optional<RowExpression> extractFunctionArgument(String name, RowExpression a, RowExpression b)
    {
        if (a instanceof CallExpression) {
            CallExpression c = (CallExpression) a;
            if (name.equalsIgnoreCase(c.getDisplayName()) && !c.getArguments().isEmpty()) {
                return Optional.of(c.getArguments().get(0));
            }
        }
        if (b instanceof CallExpression) {
            CallExpression c = (CallExpression) b;
            if (name.equalsIgnoreCase(c.getDisplayName()) && !c.getArguments().isEmpty()) {
                return Optional.of(c.getArguments().get(0));
            }
        }
        return Optional.empty();
    }

    private Optional<ConstantExpression> extractNumericLiteral(RowExpression a, RowExpression b)
    {
        if (a instanceof ConstantExpression && ((ConstantExpression) a).getValue() instanceof Number) {
            return Optional.of((ConstantExpression) a);
        }
        if (b instanceof ConstantExpression && ((ConstantExpression) b).getValue() instanceof Number) {
            return Optional.of((ConstantExpression) b);
        }
        return Optional.empty();
    }

    private Optional<ConstantExpression> extractStringLiteral(RowExpression a, RowExpression b)
    {
        if (a instanceof ConstantExpression && ((ConstantExpression) a).getValue() instanceof String) {
            return Optional.of((ConstantExpression) a);
        }
        if (b instanceof ConstantExpression && ((ConstantExpression) b).getValue() instanceof String) {
            return Optional.of((ConstantExpression) b);
        }
        return Optional.empty();
    }

    private Optional<ConstantExpression> extractTimestampLiteral(RowExpression a, RowExpression b)
    {
        if (a instanceof ConstantExpression && a.getType() instanceof TimestampType) {
            return Optional.of((ConstantExpression) a);
        }
        if (b instanceof ConstantExpression && b.getType() instanceof TimestampType) {
            return Optional.of((ConstantExpression) b);
        }
        return Optional.empty();
    }

    private Optional<CallExpression> extractDateTruncCall(RowExpression expr)
    {
        if (expr instanceof CallExpression) {
            CallExpression call = (CallExpression) expr;
            if ("date_trunc".equalsIgnoreCase(call.getDisplayName())
                    && call.getArguments().size() == 2
                    && call.getArguments().get(0) instanceof ConstantExpression) {
                return Optional.of(call);
            }
        }
        return Optional.empty();
    }
}


