/*
 * Copyright 2017 The Mifos Initiative.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.mifos.portfolio;

import com.google.gson.Gson;
import io.mifos.accounting.api.v1.domain.AccountType;
import io.mifos.accounting.api.v1.domain.Creditor;
import io.mifos.accounting.api.v1.domain.Debtor;
import io.mifos.individuallending.api.v1.domain.caseinstance.CaseParameters;
import io.mifos.individuallending.api.v1.domain.product.ChargeIdentifiers;
import io.mifos.individuallending.api.v1.domain.workflow.Action;
import io.mifos.portfolio.api.v1.domain.Case;
import io.mifos.portfolio.api.v1.domain.CostComponent;
import io.mifos.portfolio.api.v1.domain.Product;
import io.mifos.portfolio.api.v1.events.EventConstants;
import org.junit.Assert;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static io.mifos.individuallending.api.v1.events.IndividualLoanEventConstants.*;
import static io.mifos.portfolio.Fixture.MINOR_CURRENCY_UNIT_DIGITS;

/**
 * @author Myrle Krantz
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class TestAccountingInteractionInLoanWorkflow extends AbstractPortfolioTest {
  private static final BigDecimal PROCESSING_FEE_AMOUNT = BigDecimal.valueOf(10_0000, MINOR_CURRENCY_UNIT_DIGITS);
  private static final BigDecimal LOAN_ORIGINATION_FEE_AMOUNT = BigDecimal.valueOf(100_0000, MINOR_CURRENCY_UNIT_DIGITS);
  private static final BigDecimal DISBURSEMENT_FEE_AMOUNT = BigDecimal.valueOf(1_0000, MINOR_CURRENCY_UNIT_DIGITS);

  private static Product product;
  private static Case customerCase;
  private static CaseParameters caseParameters;
  private static String pendingDisbursalAccountIdentifier;
  private static String customerLoanAccountIdentifier;


  @Test
  public void step1CreateProduct() throws InterruptedException {
    //Create product and set charges to fixed fees.
    product = createProduct();

    setFeeToFixedValue(product.getIdentifier(), ChargeIdentifiers.PROCESSING_FEE_ID, PROCESSING_FEE_AMOUNT);
    setFeeToFixedValue(product.getIdentifier(), ChargeIdentifiers.LOAN_ORIGINATION_FEE_ID, LOAN_ORIGINATION_FEE_AMOUNT);
    setFeeToFixedValue(product.getIdentifier(), ChargeIdentifiers.DISBURSEMENT_FEE_ID, DISBURSEMENT_FEE_AMOUNT);

    portfolioManager.enableProduct(product.getIdentifier(), true);
    Assert.assertTrue(this.eventRecorder.wait(EventConstants.PUT_PRODUCT_ENABLE, product.getIdentifier()));
  }

  @Test
  public void step2CreateCase() throws InterruptedException {
    //Create case.
    caseParameters = Fixture.createAdjustedCaseParameters(x -> {
    });
    final String caseParametersAsString = new Gson().toJson(caseParameters);
    customerCase = createAdjustedCase(product.getIdentifier(), x -> x.setParameters(caseParametersAsString));

    checkNextActionsCorrect(product.getIdentifier(), customerCase.getIdentifier(), Action.OPEN);
    checkCostComponentForActionCorrect(product.getIdentifier(), customerCase.getIdentifier(), Action.OPEN,
        new CostComponent(ChargeIdentifiers.PROCESSING_FEE_ID, PROCESSING_FEE_AMOUNT));
  }

  //Open the case and accept a processing fee.
  @Test
  public void step3OpenCase() throws InterruptedException {
    checkStateTransfer(
        product.getIdentifier(),
        customerCase.getIdentifier(),
        Action.OPEN,
        Collections.singletonList(assignEntryToTeller()),
        OPEN_INDIVIDUALLOAN_CASE,
        Case.State.PENDING);
    checkNextActionsCorrect(product.getIdentifier(), customerCase.getIdentifier(), Action.APPROVE, Action.DENY);
    checkCostComponentForActionCorrect(product.getIdentifier(), customerCase.getIdentifier(), Action.APPROVE,
        new CostComponent(ChargeIdentifiers.LOAN_ORIGINATION_FEE_ID, LOAN_ORIGINATION_FEE_AMOUNT),
        new CostComponent(ChargeIdentifiers.LOAN_FUNDS_ALLOCATION_ID, caseParameters.getMaximumBalance().setScale(product.getMinorCurrencyUnitDigits(), BigDecimal.ROUND_UNNECESSARY)));

    AccountingFixture.verifyTransfer(ledgerManager,
        AccountingFixture.TELLER_ONE_ACCOUNT_IDENTIFIER, AccountingFixture.PROCESSING_FEE_INCOME_ACCOUNT_IDENTIFIER,
        PROCESSING_FEE_AMOUNT
    );
  }


  //Approve the case, accept a loan origination fee, and prepare to disburse the loan by earmarking the funds.
  @Test
  public void step4ApproveCase() throws InterruptedException {
    checkStateTransfer(
        product.getIdentifier(),
        customerCase.getIdentifier(),
        Action.APPROVE,
        Collections.singletonList(assignEntryToTeller()),
        APPROVE_INDIVIDUALLOAN_CASE,
        Case.State.APPROVED);
    checkNextActionsCorrect(product.getIdentifier(), customerCase.getIdentifier(), Action.DISBURSE, Action.CLOSE);

    pendingDisbursalAccountIdentifier =
        AccountingFixture.verifyAccountCreation(ledgerManager, AccountingFixture.PENDING_DISBURSAL_LEDGER_IDENTIFIER, AccountType.ASSET);
    customerLoanAccountIdentifier =
        AccountingFixture.verifyAccountCreation(ledgerManager, AccountingFixture.CUSTOMER_LOAN_LEDGER_IDENTIFIER, AccountType.ASSET);

    final Set<Debtor> debtors = new HashSet<>();
    debtors.add(new Debtor(AccountingFixture.LOAN_FUNDS_SOURCE_ACCOUNT_IDENTIFIER, caseParameters.getMaximumBalance().toPlainString()));
    debtors.add(new Debtor(AccountingFixture.TELLER_ONE_ACCOUNT_IDENTIFIER, LOAN_ORIGINATION_FEE_AMOUNT.toPlainString()));

    final Set<Creditor> creditors = new HashSet<>();
    creditors.add(new Creditor(pendingDisbursalAccountIdentifier, caseParameters.getMaximumBalance().toPlainString()));
    creditors.add(new Creditor(AccountingFixture.LOAN_ORIGINATION_FEES_ACCOUNT_IDENTIFIER, LOAN_ORIGINATION_FEE_AMOUNT.toPlainString()));
    AccountingFixture.verifyTransfer(ledgerManager, debtors, creditors);
  }

  //Approve the case, accept a loan origination fee, and prepare to disburse the loan by earmarking the funds.
  @Test
  public void step5DisburseFullAmount() throws InterruptedException {
    checkStateTransfer(
        product.getIdentifier(),
        customerCase.getIdentifier(),
        Action.DISBURSE,
        Collections.singletonList(assignEntryToTeller()),
        DISBURSE_INDIVIDUALLOAN_CASE,
        Case.State.ACTIVE);
    checkNextActionsCorrect(product.getIdentifier(), customerCase.getIdentifier(), Action.APPLY_INTEREST,
        Action.APPLY_INTEREST, Action.MARK_LATE, Action.ACCEPT_PAYMENT, Action.DISBURSE, Action.WRITE_OFF, Action.CLOSE);


    final Set<Debtor> debtors = new HashSet<>();
    debtors.add(new Debtor(pendingDisbursalAccountIdentifier, caseParameters.getMaximumBalance().toPlainString()));
    debtors.add(new Debtor(AccountingFixture.TELLER_ONE_ACCOUNT_IDENTIFIER, DISBURSEMENT_FEE_AMOUNT.toPlainString()));

    final Set<Creditor> creditors = new HashSet<>();
    creditors.add(new Creditor(customerLoanAccountIdentifier, caseParameters.getMaximumBalance().toPlainString()));
    creditors.add(new Creditor(AccountingFixture.DISBURSEMENT_FEE_INCOME_ACCOUNT_IDENTIFIER, DISBURSEMENT_FEE_AMOUNT.toPlainString()));
    AccountingFixture.verifyTransfer(ledgerManager, debtors, creditors);

  }
}