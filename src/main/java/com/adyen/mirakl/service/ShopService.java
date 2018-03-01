package com.adyen.mirakl.service;

import com.adyen.mirakl.startup.MiraklStartupValidator;
import com.adyen.model.Address;
import com.adyen.model.Name;
import com.adyen.model.marketpay.*;
import com.adyen.model.marketpay.CreateAccountHolderRequest.LegalEntityEnum;
import com.adyen.service.Account;
import com.adyen.service.exception.ApiException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.mirakl.client.mmp.domain.additionalfield.MiraklAdditionalFieldType;
import com.mirakl.client.mmp.domain.common.MiraklAdditionalFieldValue;
import com.mirakl.client.mmp.domain.common.MiraklAdditionalFieldValue.MiraklValueListAdditionalFieldValue;
import com.mirakl.client.mmp.domain.shop.MiraklContactInformation;
import com.mirakl.client.mmp.domain.shop.MiraklShop;
import com.mirakl.client.mmp.domain.shop.MiraklShops;
import com.mirakl.client.mmp.domain.shop.bank.MiraklIbanBankAccountInformation;
import com.mirakl.client.mmp.operator.core.MiraklMarketplacePlatformOperatorApiClient;
import com.mirakl.client.mmp.request.shop.MiraklGetShopsRequest;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
@Transactional
public class ShopService {
    private static final String ADYEN_UBO = "adyen-ubo";
    private static final String FIRSTNAME = "firstname";
    private static final String LASTNAME = "lastname";
    private static final String CIVILITY = "civility";
    private static final String EMAIL = "email";
    private final Logger log = LoggerFactory.getLogger(ShopService.class);

    private static Map<String, Name.GenderEnum> CIVILITY_TO_GENDER = ImmutableMap.<String, Name.GenderEnum>builder().put("Mr", Name.GenderEnum.MALE)
                                                                                                                    .put("Mrs", Name.GenderEnum.FEMALE)
                                                                                                                    .put("Miss", Name.GenderEnum.FEMALE)
                                                                                                                    .build();

    @Resource
    private MiraklMarketplacePlatformOperatorApiClient miraklMarketplacePlatformOperatorApiClient;

    @Resource
    private Account adyenAccountService;

    //TODO remove - temporary fix for delta to unblock cucumber testing
    private static final Date startupDate = new Date();

    @Value("${shopService.maxUbos}")
    private Integer maxUbos = 4;

    public void retrieveUpdatedShops() {
        List<MiraklShop> shops = getUpdatedShops();

        log.debug("Retrieved shops: {}", shops.size());
        for (MiraklShop shop : shops)
            try {
                GetAccountHolderResponse getAccountHolderResponse = getAccountHolderFromShop(shop);
                if (getAccountHolderResponse != null) {
                    processUpdateAccountHolder(shop, getAccountHolderResponse);
                } else {
                    processCreateAccountHolder(shop);
                }
            } catch (ApiException e) {
                log.error("MarketPay Api Exception: {}", e.getError(), e);
            } catch (Exception e) {
                log.error("Exception: {}", e.getMessage(), e);
            }
    }

    private void processCreateAccountHolder(final MiraklShop shop) throws Exception {
        CreateAccountHolderRequest createAccountHolderRequest = createAccountHolderRequestFromShop(shop);
        CreateAccountHolderResponse response = adyenAccountService.createAccountHolder(createAccountHolderRequest);
        log.debug("CreateAccountHolderResponse: {}", response);
    }

    private void processUpdateAccountHolder(final MiraklShop shop, final GetAccountHolderResponse getAccountHolderResponse) throws Exception {
        Optional<UpdateAccountHolderRequest> updateAccountHolderRequest = updateAccountHolderRequestFromShop(shop, getAccountHolderResponse);
        if(updateAccountHolderRequest.isPresent()){
            UpdateAccountHolderResponse response = adyenAccountService.updateAccountHolder(updateAccountHolderRequest.get());
            log.debug("UpdateAccountHolderResponse: {}", response);

            // if IBAN has changed remove the old one
            if (isIbanChanged(getAccountHolderResponse, shop)) {
                DeleteBankAccountResponse deleteBankAccountResponse = adyenAccountService.deleteBankAccount(deleteBankAccountRequest(getAccountHolderResponse));
                log.debug("DeleteBankAccountResponse: {}", deleteBankAccountResponse);
            }
        }
    }

    /**
     * Construct DeleteBankAccountRequest to remove outdated iban bankaccounts
     */
    protected DeleteBankAccountRequest deleteBankAccountRequest(GetAccountHolderResponse getAccountHolderResponse) {
        DeleteBankAccountRequest deleteBankAccountRequest = new DeleteBankAccountRequest();
        deleteBankAccountRequest.accountHolderCode(getAccountHolderResponse.getAccountHolderCode());
        List<String> uuids = new ArrayList<>();
        for (BankAccountDetail bankAccountDetail : getAccountHolderResponse.getAccountHolderDetails().getBankAccountDetails()) {
            uuids.add(bankAccountDetail.getBankAccountUUID());
        }
        deleteBankAccountRequest.setBankAccountUUIDs(uuids);

        return deleteBankAccountRequest;
    }

    public List<MiraklShop> getUpdatedShops() {
        int offset = 0;
        Long totalCount = 1L;
        List<MiraklShop> shops = new ArrayList<>();

        while (offset < totalCount) {
            MiraklGetShopsRequest miraklGetShopsRequest = new MiraklGetShopsRequest();
            miraklGetShopsRequest.setPaginate(false);
            miraklGetShopsRequest.setOffset(offset);

            miraklGetShopsRequest.setUpdatedSince(startupDate);

            MiraklShops miraklShops = miraklMarketplacePlatformOperatorApiClient.getShops(miraklGetShopsRequest);
            shops.addAll(miraklShops.getShops());

            totalCount = miraklShops.getTotalCount();
            offset += miraklShops.getShops().size();
        }

        return shops;
    }

    private CreateAccountHolderRequest createAccountHolderRequestFromShop(MiraklShop shop) {
        CreateAccountHolderRequest createAccountHolderRequest = new CreateAccountHolderRequest();

        // Set Account holder code
        createAccountHolderRequest.setAccountHolderCode(shop.getId());

        // Set LegalEntity
        LegalEntityEnum legalEntity = getLegalEntityFromShop(shop);
        createAccountHolderRequest.setLegalEntity(legalEntity);

        // Set AccountHolderDetails
        AccountHolderDetails accountHolderDetails = new AccountHolderDetails();
        accountHolderDetails.setAddress(setAddressDetails(shop));
        accountHolderDetails.setBankAccountDetails(setBankAccountDetails(shop));

        if (LegalEntityEnum.INDIVIDUAL.equals(legalEntity)) {
            IndividualDetails individualDetails = createIndividualDetailsFromShop(shop);
            accountHolderDetails.setIndividualDetails(individualDetails);
        } else if (LegalEntityEnum.BUSINESS.equals(legalEntity)) {
            BusinessDetails businessDetails = createBusinessDetailsFromShop(shop);
            accountHolderDetails.setBusinessDetails(businessDetails);
        } else {
            throw new IllegalArgumentException(legalEntity.toString() + " not supported");
        }

        // Set email
        MiraklContactInformation contactInformation = getContactInformationFromShop(shop);
        accountHolderDetails.setEmail(contactInformation.getEmail());
        createAccountHolderRequest.setAccountHolderDetails(accountHolderDetails);

        return createAccountHolderRequest;
    }

    private LegalEntityEnum getLegalEntityFromShop(MiraklShop shop) {
        MiraklValueListAdditionalFieldValue additionalFieldValue = (MiraklValueListAdditionalFieldValue) shop.getAdditionalFieldValues()
                                                                                                             .stream()
                                                                                                             .filter(field -> isListWithCode(field,
                                                                                                                                             MiraklStartupValidator.CustomMiraklFields.ADYEN_LEGAL_ENTITY_TYPE))
                                                                                                             .findAny()
                                                                                                             .orElseThrow(() -> new RuntimeException("Legal entity not found"));

        LegalEntityEnum legalEntity = Arrays.stream(LegalEntityEnum.values())
                                            .filter(legalEntityEnum -> legalEntityEnum.toString().equalsIgnoreCase(additionalFieldValue.getValue()))
                                            .findAny()
                                            .orElseThrow(() -> new RuntimeException("Invalid legal entity: " + additionalFieldValue.toString()));

        return legalEntity;
    }

    private MiraklContactInformation getContactInformationFromShop(MiraklShop shop) {
        return Optional.of(shop.getContactInformation()).orElseThrow(() -> new RuntimeException("Contact information not found"));
    }

    private Address setAddressDetails(MiraklShop shop) {
        MiraklContactInformation contactInformation = getContactInformationFromShop(shop);
        if (! contactInformation.getCountry().isEmpty()) {
            Address address = new Address();
            address.setHouseNumberOrName(getHouseNumberFromStreet(contactInformation.getStreet1()));
            address.setPostalCode(contactInformation.getZipCode());
            address.setStateOrProvince(contactInformation.getState());
            address.setStreet(contactInformation.getStreet1());
            address.setCountry(getIso2CountryCodeFromIso3(contactInformation.getCountry()));
            address.setCity(contactInformation.getCity());
            return address;
        }
        return null;
    }

    private BusinessDetails createBusinessDetailsFromShop(final MiraklShop shop) {
        BusinessDetails businessDetails = new BusinessDetails();

        if (shop.getProfessionalInformation() != null) {
            if (StringUtils.isNotEmpty(shop.getProfessionalInformation().getCorporateName())) {
                businessDetails.setLegalBusinessName(shop.getProfessionalInformation().getCorporateName());
            }
            if (StringUtils.isNotEmpty(shop.getProfessionalInformation().getTaxIdentificationNumber())) {
                businessDetails.setTaxId(shop.getProfessionalInformation().getTaxIdentificationNumber());
            }
        }

        businessDetails.setShareholders(extractUbos(shop));
        return businessDetails;
    }

    private IndividualDetails createIndividualDetailsFromShop(MiraklShop shop) {
        IndividualDetails individualDetails = new IndividualDetails();

        MiraklContactInformation contactInformation = getContactInformationFromShop(shop);

        Name name = new Name();
        name.setFirstName(contactInformation.getFirstname());
        name.setLastName(contactInformation.getLastname());
        name.setGender(CIVILITY_TO_GENDER.getOrDefault(contactInformation.getCivility(), Name.GenderEnum.UNKNOWN));
        individualDetails.setName(name);
        return individualDetails;
    }

    private boolean isListWithCode(MiraklAdditionalFieldValue additionalFieldValue, MiraklStartupValidator.CustomMiraklFields field) {
        return MiraklAdditionalFieldType.LIST.equals(additionalFieldValue.getFieldType()) && field.toString().equalsIgnoreCase(additionalFieldValue.getCode());
    }

    /**
     * Check if AccountHolder already exists in Adyen
     */
    private GetAccountHolderResponse getAccountHolderFromShop(MiraklShop shop) throws Exception {

        // lookup accountHolder in Adyen
        GetAccountHolderRequest getAccountHolderRequest = new GetAccountHolderRequest();
        getAccountHolderRequest.setAccountHolderCode(shop.getId());

        try {
            GetAccountHolderResponse getAccountHolderResponse = adyenAccountService.getAccountHolder(getAccountHolderRequest);
            if (! getAccountHolderResponse.getAccountHolderCode().isEmpty()) {
                return getAccountHolderResponse;
            }
        } catch (ApiException e) {
            // account does not exists yet
            log.debug("MarketPay Api Exception: {}", e.getError());
        }

        return null;
    }

    /**
     * Construct updateAccountHolderRequest to Adyen from Mirakl shop
     */
    protected Optional<UpdateAccountHolderRequest> updateAccountHolderRequestFromShop(MiraklShop shop, GetAccountHolderResponse getAccountHolderResponse) {
        if (shop.getPaymentInformation() instanceof MiraklIbanBankAccountInformation) {
            MiraklIbanBankAccountInformation miraklIbanBankAccountInformation = (MiraklIbanBankAccountInformation) shop.getPaymentInformation();
            if ((! miraklIbanBankAccountInformation.getIban().isEmpty() && shop.getCurrencyIsoCode() != null) &&
                // if IBAN already exists and is the same then ignore this
                (! isIbanIdentical(miraklIbanBankAccountInformation.getIban(), getAccountHolderResponse))) {
                UpdateAccountHolderRequest updateAccountHolderRequest = new UpdateAccountHolderRequest();
                updateAccountHolderRequest.setAccountHolderCode(shop.getId());
                // create AccountHolderDetails
                AccountHolderDetails accountHolderDetails = new AccountHolderDetails();
                accountHolderDetails.setBankAccountDetails(setBankAccountDetails(shop));
                updateAccountHolderRequest.setAccountHolderDetails(accountHolderDetails);
                return Optional.of(updateAccountHolderRequest);

            }
        }

        log.warn("Unable to create Account holder details, skipping update for shop: {}", shop.getId());
        return Optional.empty();
    }

    /**
     * Check if IBAN is changed
     */
    protected boolean isIbanChanged(GetAccountHolderResponse getAccountHolderResponse, MiraklShop shop) {

        if (shop.getPaymentInformation() instanceof MiraklIbanBankAccountInformation) {
            MiraklIbanBankAccountInformation miraklIbanBankAccountInformation = (MiraklIbanBankAccountInformation) shop.getPaymentInformation();
            if (! miraklIbanBankAccountInformation.getIban().isEmpty()) {
                if (getAccountHolderResponse.getAccountHolderDetails() != null && ! getAccountHolderResponse.getAccountHolderDetails().getBankAccountDetails().isEmpty()) {
                    if (! miraklIbanBankAccountInformation.getIban().equals(getAccountHolderResponse.getAccountHolderDetails().getBankAccountDetails().get(0).getIban())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Check if IBAN is the same as on Adyen side
     */
    protected boolean isIbanIdentical(String iban, GetAccountHolderResponse getAccountHolderResponse) {

        if (getAccountHolderResponse.getAccountHolderDetails() != null && ! getAccountHolderResponse.getAccountHolderDetails().getBankAccountDetails().isEmpty()) {
            if (iban.equals(getAccountHolderResponse.getAccountHolderDetails().getBankAccountDetails().get(0).getIban())) {
                return true;
            }
        }
        return false;
    }


    /**
     * Set bank account details
     */
    private List<BankAccountDetail> setBankAccountDetails(MiraklShop shop) {
        BankAccountDetail bankAccountDetail = createBankAccountDetail(shop);
        List<BankAccountDetail> bankAccountDetails = new ArrayList<>();
        bankAccountDetails.add(bankAccountDetail);
        return bankAccountDetails;
    }

    private BankAccountDetail createBankAccountDetail(MiraklShop shop) {
        if(!(shop.getPaymentInformation() instanceof MiraklIbanBankAccountInformation)){
            log.debug("No IBAN bank account details, not creating bank account detail");
            return null;
        }
        MiraklIbanBankAccountInformation miraklIbanBankAccountInformation = (MiraklIbanBankAccountInformation) shop.getPaymentInformation();

        // create AcountHolderDetails
        AccountHolderDetails accountHolderDetails = new AccountHolderDetails();

        // set BankAccountDetails
        BankAccountDetail bankAccountDetail = new BankAccountDetail();

        // check if PaymentInformation is object MiraklIbanBankAccountInformation
        miraklIbanBankAccountInformation.getIban();
        bankAccountDetail.setIban(miraklIbanBankAccountInformation.getIban());
        bankAccountDetail.setBankBicSwift(miraklIbanBankAccountInformation.getBic());
        bankAccountDetail.setCountryCode(getBankCountryFromIban(miraklIbanBankAccountInformation.getIban())); // required field
        bankAccountDetail.setCurrencyCode(shop.getCurrencyIsoCode().toString());


        if (shop.getContactInformation() != null) {
            bankAccountDetail.setOwnerPostalCode(shop.getContactInformation().getZipCode());
            bankAccountDetail.setOwnerHouseNumberOrName(getHouseNumberFromStreet(shop.getContactInformation().getStreet1()));
            bankAccountDetail.setOwnerName(shop.getContactInformation().getFirstname().concat(" ").concat(shop.getContactInformation().getLastname()));
        }

        bankAccountDetail.setPrimaryAccount(true);

        List<BankAccountDetail> bankAccountDetails = new ArrayList<>();
        bankAccountDetails.add(bankAccountDetail);
        accountHolderDetails.setBankAccountDetails(bankAccountDetails);

        return bankAccountDetail;
    }

    private List<ShareholderContact> extractUbos(final MiraklShop shop) {
        Map<String, String> extractedKeysFromMirakl = shop.getAdditionalFieldValues()
                                                          .stream()
                                                          .filter(MiraklAdditionalFieldValue.MiraklAbstractAdditionalFieldWithSingleValue.class::isInstance)
                                                          .map(MiraklAdditionalFieldValue.MiraklAbstractAdditionalFieldWithSingleValue.class::cast)
                                                          .collect(Collectors.toMap(MiraklAdditionalFieldValue::getCode,
                                                                                    MiraklAdditionalFieldValue.MiraklAbstractAdditionalFieldWithSingleValue::getValue));

        ImmutableList.Builder<ShareholderContact> builder = ImmutableList.builder();
        generateKeys().forEach((i, keys) -> {
            String firstName = extractedKeysFromMirakl.getOrDefault(keys.get(FIRSTNAME), "");
            String lastName = extractedKeysFromMirakl.getOrDefault(keys.get(LASTNAME), "");
            String civility = extractedKeysFromMirakl.getOrDefault(keys.get(CIVILITY), "");
            String email = extractedKeysFromMirakl.getOrDefault(keys.get(EMAIL), "");

            if (ImmutableList.of(firstName, lastName, civility, email).stream().noneMatch(StringUtils::isBlank)) {
                ShareholderContact shareholderContact = new ShareholderContact();
                Name name = new Name();
                name.setFirstName(firstName);
                name.setLastName(lastName);
                name.setGender(CIVILITY_TO_GENDER.getOrDefault(civility, Name.GenderEnum.UNKNOWN));
                shareholderContact.setName(name);
                shareholderContact.setEmail(email);
                builder.add(shareholderContact);
            }
        });
        return builder.build();
    }

    private Map<Integer, Map<String, String>> generateKeys() {
        return IntStream.rangeClosed(1, maxUbos).mapToObj(i -> {
            final Map<Integer, Map<String, String>> grouped = new HashMap<>();
            grouped.put(i,
                        ImmutableMap.of(FIRSTNAME,
                                        ADYEN_UBO + String.valueOf(i) + "-firstname",
                                        LASTNAME,
                                        ADYEN_UBO + String.valueOf(i) + "-lastname",
                                        CIVILITY,
                                        ADYEN_UBO + String.valueOf(i) + "-civility",
                                        EMAIL,
                                        ADYEN_UBO + String.valueOf(i) + "-email"));
            return grouped;
        }).reduce((x, y) -> {
            x.put(y.entrySet().iterator().next().getKey(), y.entrySet().iterator().next().getValue());
            return x;
        }).orElseThrow(() -> new IllegalStateException("UBOs must exist, number found: " + maxUbos));
    }

    /**
     * First two digits of IBAN holds ISO country code
     */
    private String getBankCountryFromIban(String iban) {
        return iban.substring(0, 2);
    }


    /**
     * TODO: implement method to retrieve housenumber from street
     */
    private String getHouseNumberFromStreet(String street) {
        return "1";
    }

    public Integer getMaxUbos() {
        return maxUbos;
    }

    public void setMaxUbos(Integer maxUbos) {
        this.maxUbos = maxUbos;
    }

    /**
     * Get ISO-2 Country Code from ISO-3 Country Code
     */
    protected String getIso2CountryCodeFromIso3(String iso3) {
        if (! iso3.isEmpty()) {
            return countryCodes().get(iso3);
        }
        return null;
    }

    /**
     * Do this on application start-up
     */
    public Map<String, String> countryCodes() {

        Map<String, String> countryCodes = new HashMap<>();
        String[] isoCountries = Locale.getISOCountries();

        for (String country : isoCountries) {
            Locale locale = new Locale("", country);
            countryCodes.put(locale.getISO3Country(), locale.getCountry());
        }
        return countryCodes;
    }
}