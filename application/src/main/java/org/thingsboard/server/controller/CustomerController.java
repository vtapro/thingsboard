// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.Customer;
import org.thingsboard.server.common.data.NameConflictStrategy;
import org.thingsboard.server.common.data.NameConflictPolicy;
import org.thingsboard.server.common.data.UniquifyStrategy;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.rbac.RbacCustomerHierarchy;
import org.thingsboard.server.common.data.security.Authority;
import org.thingsboard.server.config.annotations.ApiOperation;
import org.thingsboard.server.dao.settings.CustomerHierarchyService;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.entitiy.customer.TbCustomerService;
import org.thingsboard.server.service.security.model.SecurityUser;
import org.thingsboard.server.service.security.permission.Operation;
import org.thingsboard.server.service.security.permission.Resource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.thingsboard.server.controller.ControllerConstants.CUSTOMER_ID;
import static org.thingsboard.server.controller.ControllerConstants.CUSTOMER_ID_PARAM_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.CUSTOMER_TEXT_SEARCH_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.HOME_DASHBOARD;
import static org.thingsboard.server.controller.ControllerConstants.NAME_CONFLICT_POLICY_DESC;
import static org.thingsboard.server.controller.ControllerConstants.UNIQUIFY_SEPARATOR_DESC;
import static org.thingsboard.server.controller.ControllerConstants.PAGE_DATA_PARAMETERS;
import static org.thingsboard.server.controller.ControllerConstants.PAGE_NUMBER_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.PAGE_SIZE_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.SORT_ORDER_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.SORT_PROPERTY_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.TENANT_AUTHORITY_PARAGRAPH;
import static org.thingsboard.server.controller.ControllerConstants.TENANT_OR_CUSTOMER_AUTHORITY_PARAGRAPH;
import static org.thingsboard.server.controller.ControllerConstants.UNIQUIFY_STRATEGY_DESC;
import static org.thingsboard.server.controller.ControllerConstants.UUID_WIKI_LINK;

@RestController
@TbCoreComponent
@RequiredArgsConstructor
@RequestMapping("/api")
public class CustomerController extends BaseController {

    private final TbCustomerService tbCustomerService;
    private final CustomerHierarchyService customerHierarchyService;

    public static final String IS_PUBLIC = "isPublic";
    public static final String CUSTOMER_SECURITY_CHECK = "If the user has the authority of 'Tenant Administrator', the server checks that the customer is owned by the same tenant. " +
            "If the user has the authority of 'Customer User', the server checks that the user belongs to the customer.";

    @ApiOperation(value = "Get Customer (getCustomerById)",
            notes = "Get the Customer object based on the provided Customer Id. "
                    + CUSTOMER_SECURITY_CHECK + TENANT_OR_CUSTOMER_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @RequestMapping(value = "/customer/{customerId}", method = RequestMethod.GET)
    @ResponseBody
    public Customer getCustomerById(
            @Parameter(description = CUSTOMER_ID_PARAM_DESCRIPTION)
            @PathVariable(CUSTOMER_ID) String strCustomerId) throws ThingsboardException {
        checkParameter(CUSTOMER_ID, strCustomerId);
        CustomerId customerId = new CustomerId(toUUID(strCustomerId));
        Customer customer = checkCustomerId(customerId, Operation.READ);
        checkDashboardInfo(customer.getAdditionalInfo(), HOME_DASHBOARD);
        return customer;
    }


    @ApiOperation(value = "Get short Customer info (getShortCustomerInfoById)",
            notes = "Get the short customer object that contains only the title and 'isPublic' flag. "
                    + CUSTOMER_SECURITY_CHECK + TENANT_OR_CUSTOMER_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @RequestMapping(value = "/customer/{customerId}/shortInfo", method = RequestMethod.GET)
    @ResponseBody
    public JsonNode getShortCustomerInfoById(
            @Parameter(description = CUSTOMER_ID_PARAM_DESCRIPTION)
            @PathVariable(CUSTOMER_ID) String strCustomerId) throws ThingsboardException {
        checkParameter(CUSTOMER_ID, strCustomerId);
        CustomerId customerId = new CustomerId(toUUID(strCustomerId));
        Customer customer = checkCustomerId(customerId, Operation.READ);
        ObjectNode infoObject = JacksonUtil.newObjectNode();
        infoObject.put("title", customer.getTitle());
        infoObject.put(IS_PUBLIC, customer.isPublic());
        return infoObject;
    }

    @ApiOperation(value = "Get Customer Title (getCustomerTitleById)",
            notes = "Get the title of the customer. "
                    + CUSTOMER_SECURITY_CHECK + TENANT_OR_CUSTOMER_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @RequestMapping(value = "/customer/{customerId}/title", method = RequestMethod.GET, produces = "application/text")
    @ResponseBody
    public String getCustomerTitleById(
            @Parameter(description = CUSTOMER_ID_PARAM_DESCRIPTION)
            @PathVariable(CUSTOMER_ID) String strCustomerId) throws ThingsboardException {
        checkParameter(CUSTOMER_ID, strCustomerId);
        CustomerId customerId = new CustomerId(toUUID(strCustomerId));
        Customer customer = checkCustomerId(customerId, Operation.READ);
        return customer.getTitle();
    }

    @ApiOperation(value = "Create or update Customer (saveCustomer)",
            notes = "Creates or Updates the Customer. When creating customer, platform generates Customer Id as " + UUID_WIKI_LINK +
                    "The newly created Customer Id will be present in the response. " +
                    "Specify existing Customer Id to update the Customer. " +
                    "Referencing non-existing Customer Id will cause 'Not Found' error." +
                    "Remove 'id', 'tenantId' from the request body example (below) to create new Customer entity. " +
                    TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @RequestMapping(value = "/customer", method = RequestMethod.POST)
    @ResponseBody
    public Customer saveCustomer(@io.swagger.v3.oas.annotations.parameters.RequestBody(description = "A JSON value representing the customer.") @RequestBody Customer customer,
                                 @Parameter(description = NAME_CONFLICT_POLICY_DESC)
                                 @RequestParam(name = "nameConflictPolicy", defaultValue = "FAIL") NameConflictPolicy nameConflictPolicy,
                                 @Parameter(description = UNIQUIFY_SEPARATOR_DESC)
                                 @RequestParam(name = "uniquifySeparator", defaultValue = "_") String uniquifySeparator,
                                 @Parameter(description = UNIQUIFY_STRATEGY_DESC)
                                 @RequestParam(name = "uniquifyStrategy", defaultValue = "RANDOM") UniquifyStrategy uniquifyStrategy) throws Exception {
        SecurityUser currentUser = getCurrentUser();
        customer.setTenantId(currentUser.getTenantId());
        Customer oldCustomer = null;
        if (customer.getId() != null) {
            oldCustomer = checkCustomerId(customer.getId(), Operation.WRITE);
        } else {
            checkEntity(null, customer, Resource.CUSTOMER);
        }
        if (oldCustomer != null) {
            preserveRbacOwner(customer, oldCustomer);
        } else {
            saveRbacOwner(customer);
        }
        Customer savedCustomer = tbCustomerService.save(customer,
                new NameConflictStrategy(nameConflictPolicy, uniquifySeparator, uniquifyStrategy), currentUser);
        if (oldCustomer == null && Authority.CUSTOMER_USER.equals(currentUser.getAuthority())
                && currentUser.getCustomerId() != null && savedCustomer.getId() != null) {
            // the customer created by a customer user becomes a sub-customer of its own customer
            addToCustomerHierarchy(currentUser, savedCustomer.getId());
        }
        return savedCustomer;
    }

    /**
     * Creates a customer as a child of the given parent (used by the "Manage customers" page of a customer).
     */
    @ApiOperation(value = "Create a sub-customer (saveSubCustomer)",
            notes = "Creates a customer and records it as a child of the given parent customer. "
                    + TENANT_OR_CUSTOMER_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @PostMapping(value = "/customer/{parentCustomerId}/subCustomer")
    @ResponseBody
    public Customer saveSubCustomer(
            @Parameter(description = CUSTOMER_ID_PARAM_DESCRIPTION)
            @PathVariable("parentCustomerId") String strParentCustomerId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "A JSON value representing the customer.")
            @RequestBody Customer customer) throws Exception {
        checkParameter(CUSTOMER_ID, strParentCustomerId);
        SecurityUser currentUser = getCurrentUser();
        CustomerId parentCustomerId = new CustomerId(toUUID(strParentCustomerId));
        checkCustomerId(parentCustomerId, Operation.WRITE);
        customer.setId(null);
        customer.setTenantId(currentUser.getTenantId());
        checkEntity(null, customer, Resource.CUSTOMER);
        saveRbacOwner(customer);
        Customer savedCustomer = tbCustomerService.save(customer,
                new NameConflictStrategy(NameConflictPolicy.FAIL, "_", UniquifyStrategy.RANDOM), currentUser);
        if (savedCustomer.getId() != null && !parentCustomerId.equals(savedCustomer.getId())) {
            addCustomerParent(currentUser.getTenantId(), savedCustomer.getId(), parentCustomerId);
        }
        return savedCustomer;
    }

    @ApiOperation(value = "Delete Customer (deleteCustomer)",
            notes = "Deletes the Customer and all customer Users. " +
                    "All assigned Dashboards, Assets, Devices, etc. will be unassigned but not deleted. " +
                    "Referencing non-existing Customer Id will cause an error." + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @RequestMapping(value = "/customer/{customerId}", method = RequestMethod.DELETE)
    @ResponseStatus(value = HttpStatus.OK)
    public void deleteCustomer(@Parameter(description = CUSTOMER_ID_PARAM_DESCRIPTION)
                               @PathVariable(CUSTOMER_ID) String strCustomerId) throws ThingsboardException {
        checkParameter(CUSTOMER_ID, strCustomerId);
        CustomerId customerId = new CustomerId(toUUID(strCustomerId));
        Customer customer = checkCustomerId(customerId, Operation.DELETE);
        SecurityUser currentUser = getCurrentUser();
        tbCustomerService.delete(customer, currentUser);
        if (Authority.CUSTOMER_USER.equals(currentUser.getAuthority())) {
            removeFromCustomerHierarchy(currentUser.getTenantId(), customerId);
        }
    }

    @ApiOperation(value = "Get Tenant Customers (getCustomers)",
            notes = "Returns a page of customers owned by tenant. " +
                    PAGE_DATA_PARAMETERS + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @GetMapping(value = "/customers")
    public PageData<Customer> getCustomers(
            @Parameter(description = PAGE_SIZE_DESCRIPTION, required = true)
            @RequestParam int pageSize,
            @Parameter(description = PAGE_NUMBER_DESCRIPTION, required = true)
            @RequestParam int page,
            @Parameter(description = CUSTOMER_TEXT_SEARCH_DESCRIPTION)
            @RequestParam(required = false) String textSearch,
            @Parameter(description = SORT_PROPERTY_DESCRIPTION, schema = @Schema(allowableValues = {"createdTime", "title", "email", "country", "city"}))
            @RequestParam(required = false) String sortProperty,
            @Parameter(description = SORT_ORDER_DESCRIPTION, schema = @Schema(allowableValues = {"ASC", "DESC"}))
            @RequestParam(required = false) String sortOrder) throws ThingsboardException {
        PageLink pageLink = createPageLink(pageSize, page, textSearch, sortProperty, sortOrder);
        SecurityUser currentUser = getCurrentUser();
        TenantId tenantId = currentUser.getTenantId();
        Set<UUID> scope = accessControlService.getAllowedEntityIds(currentUser, Resource.CUSTOMER, Operation.READ);
        CustomerId currentCustomerId = currentUser.getCustomerId();
        if (currentCustomerId != null && !currentCustomerId.isNullUid()) {
            // a customer user always sees its own customer at least, and the sub-customers when its role allows it
            Set<UUID> accessible = accessControlService.getAccessibleCustomerIds(currentUser);
            if (accessible == null) {
                accessible = Set.of(currentCustomerId.getId());
            }
            scope = intersect(scope, accessible);
        }
        return checkNotNull(fetchEntityScope(scope, pageLink,
                link -> customerService.findCustomersByTenantId(tenantId, link), Customer::getId));
    }

    @ApiOperation(value = "Get sub-customers of a customer (getSubCustomers)",
            notes = "Returns a page of the customers whose parent is the given customer (customer hierarchy). " +
                    PAGE_DATA_PARAMETERS + TENANT_OR_CUSTOMER_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @GetMapping(value = "/customer/{customerId}/subCustomers")
    public PageData<Customer> getSubCustomers(
            @Parameter(description = CUSTOMER_ID_PARAM_DESCRIPTION)
            @PathVariable(CUSTOMER_ID) String strCustomerId,
            @Parameter(description = PAGE_SIZE_DESCRIPTION, required = true)
            @RequestParam int pageSize,
            @Parameter(description = PAGE_NUMBER_DESCRIPTION, required = true)
            @RequestParam int page,
            @Parameter(description = CUSTOMER_TEXT_SEARCH_DESCRIPTION)
            @RequestParam(required = false) String textSearch,
            @Parameter(description = SORT_PROPERTY_DESCRIPTION, schema = @Schema(allowableValues = {"createdTime", "title", "email", "country", "city"}))
            @RequestParam(required = false) String sortProperty,
            @Parameter(description = SORT_ORDER_DESCRIPTION, schema = @Schema(allowableValues = {"ASC", "DESC"}))
            @RequestParam(required = false) String sortOrder) throws ThingsboardException {
        checkParameter(CUSTOMER_ID, strCustomerId);
        CustomerId customerId = new CustomerId(toUUID(strCustomerId));
        checkCustomerId(customerId, Operation.READ);
        SecurityUser currentUser = getCurrentUser();
        TenantId tenantId = currentUser.getTenantId();
        PageLink pageLink = createPageLink(pageSize, page, textSearch, sortProperty, sortOrder);
        List<CustomerId> childIds = new ArrayList<>();
        for (Map.Entry<String, String> entry : customerHierarchyService.getCustomerHierarchy(tenantId).getParents().entrySet()) {
            if (customerId.getId().toString().equals(entry.getValue())) {
                try {
                    childIds.add(new CustomerId(UUID.fromString(entry.getKey())));
                } catch (IllegalArgumentException ignored) {
                    // an invalid id in the hierarchy document is skipped
                }
            }
        }
        if (childIds.isEmpty()) {
            return new PageData<>(List.of(), 0, 0, false);
        }
        Set<UUID> allowedEntityIds = accessControlService.getAllowedEntityIds(currentUser, Resource.CUSTOMER, Operation.READ);
        List<Customer> children = new ArrayList<>(customerService.findCustomersByTenantIdAndIds(tenantId, childIds));
        children.removeIf(customer -> allowedEntityIds != null && !allowedEntityIds.contains(customer.getId().getId()));
        return pageCustomersInMemory(children, pageLink, sortProperty, sortOrder, textSearch);
    }

    /**
     * The customer hierarchy is stored as a tenant settings document, so the sub-customer page is built in memory
     * (the number of sub-customers of one customer is expected to stay small).
     */
    private static PageData<Customer> pageCustomersInMemory(List<Customer> customers, PageLink pageLink,
                                                            String sortProperty, String sortOrder, String textSearch) {
        String term = textSearch == null ? null : textSearch.trim().toLowerCase();
        if (term != null && !term.isEmpty()) {
            customers.removeIf(customer -> !containsIgnoreCase(customer.getTitle(), term)
                    && !containsIgnoreCase(customer.getEmail(), term));
        }
        Comparator<Customer> comparator = switch (sortProperty == null ? "createdTime" : sortProperty) {
            case "title" -> Comparator.comparing(c -> c.getTitle() == null ? "" : c.getTitle(), String.CASE_INSENSITIVE_ORDER);
            case "email" -> Comparator.comparing(c -> c.getEmail() == null ? "" : c.getEmail(), String.CASE_INSENSITIVE_ORDER);
            case "country" -> Comparator.comparing(c -> c.getCountry() == null ? "" : c.getCountry(), String.CASE_INSENSITIVE_ORDER);
            case "city" -> Comparator.comparing(c -> c.getCity() == null ? "" : c.getCity(), String.CASE_INSENSITIVE_ORDER);
            default -> Comparator.comparing(Customer::getCreatedTime);
        };
        if (sortOrder == null || !"ASC".equalsIgnoreCase(sortOrder)) {
            comparator = comparator.reversed();
        }
        customers.sort(comparator);
        int pageSize = Math.max(pageLink.getPageSize(), 1);
        int from = Math.min(pageLink.getPage() * pageSize, customers.size());
        int to = Math.min(from + pageSize, customers.size());
        List<Customer> content = new ArrayList<>(customers.subList(from, to));
        int totalPages = (int) Math.ceil((double) customers.size() / pageSize);
        return new PageData<>(content, totalPages, customers.size(), to < customers.size());
    }

    private static boolean containsIgnoreCase(String value, String term) {
        return value != null && value.toLowerCase().contains(term);
    }

    @ApiOperation(value = "Get Tenant Customer by Customer title (getTenantCustomer)",
            notes = "Get the Customer using Customer Title. " + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @GetMapping(value = "/tenant/customers")
    public Customer getTenantCustomer(
            @Parameter(description = "A string value representing the Customer title.")
            @RequestParam String customerTitle) throws ThingsboardException {
        TenantId tenantId = getCurrentUser().getTenantId();
        Customer customer = checkNotNull(customerService.findCustomerByTenantIdAndTitle(tenantId, customerTitle),
                "Customer with title [" + customerTitle + "] is not found");
        checkCustomerId(customer.getId(), Operation.READ);
        return customer;
    }

    private void addToCustomerHierarchy(SecurityUser currentUser, CustomerId childId) {
        addCustomerParent(currentUser.getTenantId(), childId, currentUser.getCustomerId());
    }

    private void addCustomerParent(TenantId tenantId, CustomerId childId, CustomerId parentId) {
        RbacCustomerHierarchy hierarchy = customerHierarchyService.getCustomerHierarchy(tenantId);
        if (hierarchy == null) {
            hierarchy = new RbacCustomerHierarchy();
        }
        hierarchy.getParents().put(childId.getId().toString(), parentId.getId().toString());
        customerHierarchyService.saveCustomerHierarchy(tenantId, hierarchy);
    }

    private void removeFromCustomerHierarchy(TenantId tenantId, CustomerId customerId) {
        RbacCustomerHierarchy hierarchy = customerHierarchyService.getCustomerHierarchy(tenantId);
        if (hierarchy != null && hierarchy.getParents() != null
                && hierarchy.getParents().remove(customerId.getId().toString()) != null) {
            customerHierarchyService.saveCustomerHierarchy(tenantId, hierarchy);
        }
    }

    private static Set<UUID> intersect(Set<UUID> first, Set<UUID> second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        Set<UUID> result = new HashSet<>(first);
        result.retainAll(second);
        return result;
    }

    @Hidden
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN')")
    @GetMapping(value = "/customers", params = {"customerIds"})
    public List<Customer> getCustomersByIdsV1(
            @Parameter(description = "A list of customer ids, separated by comma ','", array = @ArraySchema(schema = @Schema(type = "string")), required = true)
            @RequestParam("customerIds") Set<UUID> customerUUIDs) throws ThingsboardException {
        TenantId tenantId = getCurrentUser().getTenantId();
        List<CustomerId> customerIds = new ArrayList<>();
        for (UUID customerUUID : customerUUIDs) {
            customerIds.add(new CustomerId(customerUUID));
        }
        return customerService.findCustomersByTenantIdAndIds(tenantId, customerIds);
    }

    @ApiOperation(value = "Get customers by Customer Ids (getCustomersByIds)",
            notes = "Returns a list of Customer objects based on the provided ids." +
                    TENANT_OR_CUSTOMER_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN')")
    @GetMapping(value = "/customers/list")
    public List<Customer> getCustomersByIds(
            @Parameter(description = "A list of customer ids, separated by comma ','", array = @ArraySchema(schema = @Schema(type = "string")), required = true)
            @RequestParam("customerIds") Set<UUID> customerUUIDs) throws ThingsboardException {
        return getCustomersByIdsV1(customerUUIDs);
    }

}
