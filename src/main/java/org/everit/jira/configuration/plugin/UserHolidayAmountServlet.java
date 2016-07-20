/*
 * Copyright (C) 2011 Everit Kft. (http://www.everit.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.everit.jira.configuration.plugin;

import java.io.IOException;
import java.sql.Connection;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.everit.jira.configuration.plugin.schema.qdsl.QDateRange;
import org.everit.jira.configuration.plugin.schema.qdsl.QUserHolidayAmount;
import org.everit.jira.configuration.plugin.util.AvatarUtil;
import org.everit.jira.configuration.plugin.util.QueryResultWithCount;
import org.everit.jira.querydsl.schema.QAvatar;
import org.everit.jira.querydsl.schema.QCwdUser;
import org.everit.web.partialresponse.PartialResponseBuilder;

import com.querydsl.core.types.ConstantImpl;
import com.querydsl.core.types.Expression;
import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Coalesce;
import com.querydsl.sql.Configuration;
import com.querydsl.sql.SQLQuery;
import com.querydsl.sql.dml.SQLDeleteClause;
import com.querydsl.sql.dml.SQLInsertClause;
import com.querydsl.sql.dml.SQLUpdateClause;

/**
 * Assigning holiday amounts to users.
 */
public class UserHolidayAmountServlet extends AbstractPageServlet {

  public static class UserHolidayAmountDTO {
    public int amount;

    public Long avatarId;

    public String avatarOwner;

    public String description;

    public String displayName;

    public Date endDateExcluded;

    public Date startDate;

    public long userHolidayAmountId;

    public String userName;

    public Date getEndDate() {
      return new java.sql.Date(endDateExcluded.getTime() - MILLISECS_IN_DAY);
    }
  }

  private static final int MILLISECS_IN_DAY = 3600 * 24 * 1000;

  private static final int PAGE_SIZE = 50;

  private static final PaginationComponent PAGINATION_TEMPLATE = new PaginationComponent();

  private static final long serialVersionUID = 1073648466982165361L;

  @Override
  protected Map<String, Object> createCommonVars(final HttpServletRequest req,
      final HttpServletResponse resp)
      throws IOException {

    Map<String, Object> commonVars = super.createCommonVars(req, resp);
    commonVars.put("pagination", PAGINATION_TEMPLATE);
    return commonVars;
  }

  private void delete(final Long userHolidayAmountId) {
    transactionTemplate.execute(() -> querydslSupport.execute((connection, configuration) -> {
      Long dateRangeId = getDateRangeId(userHolidayAmountId, connection, configuration);

      QUserHolidayAmount qUserHolidayAmount = QUserHolidayAmount.userHolidayAmount;
      new SQLDeleteClause(connection, configuration, qUserHolidayAmount)
          .where(qUserHolidayAmount.userHolidayAmountId.eq(userHolidayAmountId)).execute();

      QDateRange qDateRange = QDateRange.dateRange;
      new SQLDeleteClause(connection, configuration, qDateRange)
          .where(qDateRange.dateRangeId.eq(dateRangeId)).execute();
      return null;
    }));
  }

  @Override
  protected void doGetInternal(final HttpServletRequest req, final HttpServletResponse resp,
      final Map<String, Object> vars) throws ServletException, IOException {

    boolean currentTimeRangesFilter = Boolean.valueOf(req.getParameter("currentTimeRangesFilter"));
    String userFilter = req.getParameter("userFilter");
    int pageIndex = Integer.parseInt(Objects.toString(req.getParameter("pageIndex"), "1"));

    vars.put("currentTimeRangesFilter", currentTimeRangesFilter);
    vars.put("userFilter", userFilter);
    vars.put("pageIndex", pageIndex);
    vars.put("pageSize", PAGE_SIZE);
    vars.put("userHolidayAmounts",
        getUserHolidayAmounts(userFilter, currentTimeRangesFilter, pageIndex));

    if ("XMLHttpRequest".equals(req.getHeader("X-Requested-With"))) {
      try (PartialResponseBuilder prb = new PartialResponseBuilder(resp)) {
        prb.replace("#holiday-amount-table",
            (writer) -> pageTemplate.render(writer, vars, resp.getLocale(),
                "holiday-amount-table"));
      }
    } else {
      super.doGetInternal(req, resp, vars);
    }
  }

  @Override
  protected void doPost(final HttpServletRequest req, final HttpServletResponse resp)
      throws ServletException, IOException {

    if (!checkWebSudo(req, resp)) {
      return;
    }

    String action = req.getParameter("action");

    if ("delete".equals(action)) {
      Long userHolidayAmountId = Long.parseLong(req.getParameter("userholidayamount-id"));
      delete(userHolidayAmountId);
      renderPostAnswer(req, resp, "Record deleted");
      return;
    }

    String userParam = req.getParameter("user");

    String startDateParam = req.getParameter("start-date");
    String endDateParam = req.getParameter("end-date");
    String amountParam = req.getParameter("amount");
    String description = req.getParameter("description");

    Long userId = getUserId(userParam);
    if (userId == null) {
      renderAlert("User does not exist", "error", req, resp);
      resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      return;
    }

    SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd");
    Date startDate;
    Date endDate;

    try {
      startDate = simpleDateFormat.parse(startDateParam);
      endDate = simpleDateFormat.parse(endDateParam);
    } catch (ParseException e) {
      throw new RuntimeException(e);
    }

    java.sql.Date startSqlDate = new java.sql.Date(startDate.getTime());
    java.sql.Date endSqlDateExcluded = new java.sql.Date(endDate.getTime() + MILLISECS_IN_DAY);
    Integer amountInHours = Integer.parseInt(amountParam);

    if (startDate.compareTo(endDate) > 0) {
      renderAlert("Start date must be before end date", "error", req, resp);
      resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      return;
    }

    Long userHolidayAmountId =
        ("edit".equals(action)) ? Long.parseLong(req.getParameter("userholidayamount-id")) : null;

    if (hasOverlapping(userId, startSqlDate, endSqlDateExcluded, userHolidayAmountId)) {
      renderAlert("Another range overlaps this one for the same user", "error", req, resp);
      resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      return;
    }

    int amountInSeconds = amountInHours * 3600;

    String message = null;
    if ("new".equals(action)) {
      saveNew(userId, startSqlDate, endSqlDateExcluded, amountInSeconds, description);
      message = "New record saved";
    } else if ("edit".equals(action)) {
      update(userHolidayAmountId, userId, startSqlDate, endSqlDateExcluded, amountInSeconds,
          description);
      message = "Record updated";
    }

    renderPostAnswer(req, resp, message);
  }

  private Long getDateRangeId(final Long userHolidayAmountId, final Connection connection,
      final Configuration configuration) {

    QDateRange qDateRange = QDateRange.dateRange;
    QUserHolidayAmount qUserHolidayAmount = QUserHolidayAmount.userHolidayAmount;

    Long dateRangeId = new SQLQuery<Long>(connection, configuration)
        .select(qDateRange.dateRangeId)
        .from(qUserHolidayAmount)
        .innerJoin(qUserHolidayAmount.fk.dateRangeFK, qDateRange)
        .where(qUserHolidayAmount.userHolidayAmountId.eq(userHolidayAmountId))
        .fetchOne();

    return dateRangeId;
  }

  @Override
  protected String getTemplateBase() {
    return "/META-INF/pages/user_holiday_amount";
  }

  private QueryResultWithCount<UserHolidayAmountDTO> getUserHolidayAmounts(final String user,
      final boolean currentTimeRanges, final int pageIndex) {

    return querydslSupport.execute((connection, configuration) -> {
      QUserHolidayAmount qUserHolidayAmount = QUserHolidayAmount.userHolidayAmount;
      QCwdUser qUser = QCwdUser.cwdUser;
      QDateRange qDateRange = QDateRange.dateRange;
      SQLQuery<UserHolidayAmountDTO> query = new SQLQuery<>(connection, configuration);
      query.from(qUserHolidayAmount)
          .innerJoin(qUser).on(qUserHolidayAmount.userId.eq(qUser.id))
          .innerJoin(qDateRange).on(qUserHolidayAmount.dateRangeId.eq(qDateRange.dateRangeId));

      QAvatar qAvatar = AvatarUtil.joinAvatarToCwdUser(query, qUser, "avatar");

      Expression<Long> defaultAvatarId = ConstantImpl.create(AvatarUtil.DEFAULT_AVATAR_ID);

      query
          .select(Projections.fields(UserHolidayAmountDTO.class,
              qUserHolidayAmount.userHolidayAmountId, qUser.userName, qUser.displayName,
              qDateRange.startDate, qDateRange.endDateExcluded, qUserHolidayAmount.amount,
              qUserHolidayAmount.description,
              new Coalesce<>(Long.class, qAvatar.id, defaultAvatarId).as("avatarId"),
              qAvatar.owner.as("avatarOwner")));

      List<Predicate> predicates = new ArrayList<>();
      if (user != null) {
        predicates.add(qUser.userName.eq(user));
      }
      if (currentTimeRanges) {
        java.sql.Date currentDate = new java.sql.Date(new Date().getTime());
        predicates.add(
            qDateRange.startDate.loe(currentDate)
                .and(qDateRange.endDateExcluded.gt(currentDate)));
      }

      query.where(predicates.toArray(new Predicate[0]));

      long count = query.fetchCount();

      query.orderBy(qUser.userName.asc(), qDateRange.endDateExcluded.desc());
      query.limit(PAGE_SIZE);
      query.offset((pageIndex - 1) * PAGE_SIZE);

      List<UserHolidayAmountDTO> resultSet = query.fetch();

      return new QueryResultWithCount<>(resultSet, count);
    });
  }

  private Long getUserId(final String user) {
    if (user == null) {
      return null;
    }
    return querydslSupport.execute((connection, configuration) -> {
      QCwdUser qUser = QCwdUser.cwdUser;
      return new SQLQuery<Long>(connection, configuration)
          .select(qUser.id).from(qUser)
          .where(qUser.lowerUserName.eq(user.toLowerCase()))
          .fetchOne();
    });
  }

  private boolean hasOverlapping(final Long userId, final java.sql.Date startDate,
      final java.sql.Date endDateExcluded, final Long userHolidayAmountIdToExclude) {
    Long count = querydslSupport.execute((connection, configuration) -> {
      QDateRange qDateRange = QDateRange.dateRange;
      QUserHolidayAmount qUserHolidayAmount = QUserHolidayAmount.userHolidayAmount;
      SQLQuery<Long> query = new SQLQuery<>(connection, configuration)
          .select(qDateRange.dateRangeId)
          .from(qDateRange)
          .innerJoin(qUserHolidayAmount)
          .on(qDateRange.dateRangeId.eq(qUserHolidayAmount.dateRangeId));

      List<Predicate> predicates = new ArrayList<>();
      predicates.add(qUserHolidayAmount.userId.eq(userId));

      if (userHolidayAmountIdToExclude != null) {
        predicates.add(qUserHolidayAmount.userHolidayAmountId.ne(userHolidayAmountIdToExclude));
      }

      predicates.add(rangeOverlaps(qDateRange, startDate, endDateExcluded));

      query.where(predicates.toArray(new Predicate[predicates.size()]));
      return query.fetchCount();
    });
    return count > 0;
  }

  @Override
  protected boolean isWebSudoNecessary() {
    return true;
  }

  private BooleanExpression rangeOverlaps(final QDateRange qDateRange,
      final java.sql.Date startSQLDate,
      final java.sql.Date endSQLDate) {
    return qDateRange.startDate.loe(startSQLDate)
        .and(qDateRange.endDateExcluded.gt(startSQLDate))
        .or(qDateRange.startDate.lt(endSQLDate)
            .and(qDateRange.endDateExcluded.goe(endSQLDate)));
  }

  private void renderAlert(final String message, final String alertType,
      final HttpServletRequest req, final HttpServletResponse resp) {

    try (PartialResponseBuilder prb = new PartialResponseBuilder(resp)) {
      renderAlertOnPrb(message, alertType, prb, resp.getLocale());
    }
  }

  private void renderAlertOnPrb(final String message, final String alertType,
      final PartialResponseBuilder prb, final Locale locale) {

    prb.append("#aui-message-bar",
        (writer) -> AlertComponent.INSTANCE.render(writer, message, alertType, locale));
  }

  private void renderPostAnswer(final HttpServletRequest req, final HttpServletResponse resp,
      final String message) throws IOException {
    try (PartialResponseBuilder prb = new PartialResponseBuilder(resp)) {
      renderAlertOnPrb(message, "info", prb, resp.getLocale());

      String userFilterParam = req.getParameter("userFilter");
      boolean currentTimeRanges = Boolean.valueOf(req.getParameter("currentTimeRangesFilter"));
      int pageIndex = Integer.parseInt(Objects.toString(req.getParameter("pageIndex"), "1"));

      Map<String, Object> vars = createCommonVars(req, resp);
      vars.put("userFilter", userFilterParam);
      vars.put("currentTimeRangesFilter", currentTimeRanges);

      QueryResultWithCount<UserHolidayAmountDTO> userHolidayAmounts =
          getUserHolidayAmounts(userFilterParam, currentTimeRanges, pageIndex);

      if (userHolidayAmounts.count > 0 && userHolidayAmounts.resultSet.size() == 0) {
        pageIndex = 1;
        userHolidayAmounts = getUserHolidayAmounts(userFilterParam, currentTimeRanges, pageIndex);
      }

      vars.put("pageIndex", pageIndex);
      vars.put("pageSize", PAGE_SIZE);

      vars.put("userHolidayAmounts", userHolidayAmounts);

      prb.replace("#holiday-amount-table", (writer) -> {
        pageTemplate.render(writer, vars, resp.getLocale(), "holiday-amount-table");
      });
    }
  }

  private void saveNew(final Long userId, final java.sql.Date startDate,
      final java.sql.Date endDateExcluded, final int amountInSeconds, final String description) {

    transactionTemplate.execute(() -> {
      return querydslSupport.execute((connection, configuration) -> {
        QUserHolidayAmount qUserHolidayAmount = QUserHolidayAmount.userHolidayAmount;
        QDateRange qDateRange = QDateRange.dateRange;

        Long dateRangeId = new SQLInsertClause(connection, configuration, qDateRange)
            .set(qDateRange.startDate, startDate)
            .set(qDateRange.endDateExcluded, endDateExcluded)
            .executeWithKey(qDateRange.dateRangeId);

        new SQLInsertClause(connection, configuration, qUserHolidayAmount)
            .set(qUserHolidayAmount.userId, userId)
            .set(qUserHolidayAmount.dateRangeId, dateRangeId)
            .set(qUserHolidayAmount.amount, amountInSeconds)
            .set(qUserHolidayAmount.description, description).execute();
        return null;
      });
    });

  }

  private void update(final Long userHolidayAmountId, final Long userId,
      final java.sql.Date startSqlDate,
      final java.sql.Date endSqlDateExcluded, final int amountInSeconds, final String description) {

    transactionTemplate.execute(() -> querydslSupport.execute((connection, configuration) -> {
      QDateRange qDateRange = QDateRange.dateRange;
      QUserHolidayAmount qUserHolidayAmount = QUserHolidayAmount.userHolidayAmount;

      Long dateRangeId =
          getDateRangeId(userHolidayAmountId, connection, configuration);

      new SQLUpdateClause(connection, configuration, qDateRange)
          .set(qDateRange.startDate, startSqlDate)
          .set(qDateRange.endDateExcluded, endSqlDateExcluded)
          .where(qDateRange.dateRangeId.eq(dateRangeId)).execute();

      new SQLUpdateClause(connection, configuration, qUserHolidayAmount)
          .set(qUserHolidayAmount.userId, userId)
          .set(qUserHolidayAmount.amount, amountInSeconds)
          .set(qUserHolidayAmount.description, description)
          .where(qUserHolidayAmount.userHolidayAmountId.eq(userHolidayAmountId))
          .execute();
      return null;
    }));
  }
}
