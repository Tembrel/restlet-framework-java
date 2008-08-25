/**
 * Copyright 2005-2008 Noelios Technologies.
 * 
 * The contents of this file are subject to the terms of the following open
 * source licenses: LGPL 3.0 or LGPL 2.1 or CDDL 1.0 (the "Licenses"). You can
 * select the license that you prefer but you may not use this file except in
 * compliance with one of these Licenses.
 * 
 * You can obtain a copy of the LGPL 3.0 license at
 * http://www.gnu.org/licenses/lgpl-3.0.html
 * 
 * You can obtain a copy of the LGPL 2.1 license at
 * http://www.gnu.org/licenses/lgpl-2.1.html
 * 
 * You can obtain a copy of the CDDL 1.0 license at
 * http://www.sun.com/cddl/cddl.html
 * 
 * See the Licenses for the specific language governing permissions and
 * limitations under the Licenses.
 * 
 * Alternatively, you can obtain a royaltee free commercial license with less
 * limitations, transferable or non-transferable, directly at
 * http://www.noelios.com/products/restlet-engine
 * 
 * Restlet is a registered trademark of Noelios Technologies.
 */
package org.restlet.ext.jaxrs.internal.core;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ws.rs.MatrixParam;
import javax.ws.rs.PathParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Cookie;
import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.PathSegment;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.core.Variant;
import javax.ws.rs.core.Response.ResponseBuilder;

import org.restlet.data.ChallengeResponse;
import org.restlet.data.ChallengeScheme;
import org.restlet.data.CharacterSet;
import org.restlet.data.Conditions;
import org.restlet.data.Dimension;
import org.restlet.data.Form;
import org.restlet.data.Language;
import org.restlet.data.Method;
import org.restlet.data.Reference;
import org.restlet.data.Request;
import org.restlet.data.Status;
import org.restlet.data.Tag;
import org.restlet.ext.jaxrs.RoleChecker;
import org.restlet.ext.jaxrs.internal.todo.NotYetImplementedException;
import org.restlet.ext.jaxrs.internal.util.Converter;
import org.restlet.ext.jaxrs.internal.util.EmptyIterator;
import org.restlet.ext.jaxrs.internal.util.SecurityUtil;
import org.restlet.ext.jaxrs.internal.util.SortedMetadata;
import org.restlet.ext.jaxrs.internal.util.Util;
import org.restlet.resource.Representation;

/**
 * Contains all request specific data of the interfaces injectable for &#64;
 * {@link Context}. Implemetation of the JAX-RS interfaces {@link HttpHeaders},
 * {@link UriInfo}, {@link javax.ws.rs.core.Request} and
 * {@link SecurityContext} .<br>
 * This class is not required to be thread safe, because it is only used for one
 * client request in one thread at the same time.
 * 
 * @author Stephan Koops
 */
public class CallContext implements javax.ws.rs.core.Request, HttpHeaders,
        SecurityContext {

    /**
     * Iterator to return the values for a matrix parameter.
     * 
     * @author Stephan Koops
     */
    private static class MatrixParamEncIter implements Iterator<String> {

        /** Iterates over the matrix parameters of one path segment */
        private Iterator<Map.Entry<String, List<String>>> matrixParamIter;

        private final String mpName;

        private Iterator<String> mpValueIter;

        private String nextMpValue;

        private final Iterator<PathSegment> pathSegmentIter;

        MatrixParamEncIter(String mpName, List<PathSegment> pathSegmentsEnc) {
            this.pathSegmentIter = pathSegmentsEnc.iterator();
            this.mpName = mpName;
        }

        /**
         * @see java.util.Iterator#hasNext()
         */
        public boolean hasNext() {
            if (this.nextMpValue != null) {
                return true;
            }
            while ((this.mpValueIter != null) && (this.mpValueIter.hasNext())) {
                this.nextMpValue = this.mpValueIter.next();
                return true;
            }
            while ((this.matrixParamIter != null)
                    && (matrixParamIter.hasNext())) {
                final Map.Entry<String, List<String>> entry = matrixParamIter
                        .next();
                if (entry.getKey().equals(this.mpName)) {
                    this.mpValueIter = entry.getValue().iterator();
                    return hasNext();
                }
            }
            while (this.pathSegmentIter.hasNext()) {
                this.matrixParamIter = this.pathSegmentIter.next()
                        .getMatrixParameters().entrySet().iterator();
                return hasNext();
            }
            return false;
        }

        /**
         * @see java.util.Iterator#next()
         */
        public String next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            final String nextMpValue = this.nextMpValue;
            this.nextMpValue = null;
            return nextMpValue;
        }

        /**
         * @see java.util.Iterator#remove()
         */
        public void remove() {
            throw new UnsupportedOperationException("unmodifiable");
        }
    }

    private static final int STATUS_PREC_FAILED = Status.CLIENT_ERROR_PRECONDITION_FAILED
            .getCode();

    private static final Logger unexpectedLogger = Logger
            .getLogger("JaxRsUriInfo.unexpected");

    /**
     * the unmodifiable List of accepted labuages. Lazy initialization by
     * getter.
     * 
     * @see #getAcceptableLanguages()
     */
    private List<Locale> acceptedLanguages;

    /**
     * the unmodifiable List of accepted {@link MediaType}s. Lazy
     * initialization by getter.
     * 
     * @see #getAcceptableMediaTypes()
     */
    private List<MediaType> acceptedMediaTypes;

    private final SortedMetadata<org.restlet.data.MediaType> accMediaTypes;

    /** contains the current value of the ancestor resources */
    private final LinkedList<Object> matchedResources = new LinkedList<Object>();

    /** contains the current value of the ancestor resource URIs */
    private final LinkedList<String> matchedURIs = new LinkedList<String>();

    private String baseUri;

    private Map<String, Cookie> cookies;

    private Locale language;

    private MediaType mediaType;

    private MultivaluedMap<String, String> pathParametersDecoded;

    /** is null, if no templateParameters given on creation */
    private MultivaluedMap<String, String> pathParametersEncoded;

    private List<PathSegment> pathSegmentsDecoded = null;

    private List<PathSegment> pathSegmentsEncoded = null;

    private MultivaluedMap<String, String> queryParametersDecoded;

    private MultivaluedMap<String, String> queryParametersEncoded;

    private boolean readOnly = false;

    private final Reference referenceCut;

    private final Reference referenceOriginal;

    private final Request request;

    private UnmodifiableMultivaluedMap<String, String> requestHeaders;

    private final org.restlet.data.Response response;

    private final RoleChecker roleChecker;

    /**
     * 
     * @param request
     *                The Restlet request to wrap. Must not be null.
     * @param templateParametersEncoded
     *                The template parameters. Must not be null.
     * @param response
     *                The Restlet response
     * @param roleChecker
     *                The roleChecker is needed to check, if a user is in a
     *                role, see {@link #isUserInRole(String)}. If null was
     *                given here and
     *                {@link SecurityContext#isUserInRole(String)} is called,
     *                the HTTP client will get an Internal Server Error as
     *                response.
     */
    public CallContext(Request request, org.restlet.data.Response response,
            RoleChecker roleChecker) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "The Restlet Request must not be null");
        }
        if (response == null) {
            throw new IllegalArgumentException(
                    "The Restlet Response must not be null");
        }
        if (roleChecker == null) {
            throw new IllegalArgumentException(
                    "The RoleChecker must not be null.");
        }
        final Reference referenceCut = request.getResourceRef();
        if (referenceCut == null) {
            throw new IllegalArgumentException(
                    "The request reference must not be null");
        }
        if (referenceCut.getBaseRef() == null) {
            throw new IllegalArgumentException(
                    "The request reference must contains a baseRef");
        }
        final Reference referenceOriginal = request.getOriginalRef();
        if (referenceOriginal == null) {
            throw new IllegalArgumentException(
                    "The request.originalRef must not be null");
        }
        this.referenceCut = referenceCut;
        this.referenceOriginal = referenceOriginal;
        this.readOnly = false;
        this.request = request;
        this.response = response;
        this.roleChecker = roleChecker;
        this.accMediaTypes = SortedMetadata.getForMediaTypes(request
                .getClientInfo().getAcceptedMediaTypes());
    }

    /**
     * also useable after {@link #setReadOnly()}
     * 
     * @param resourceObject
     * @param newUriPart
     * @throws URISyntaxException
     * @see UriInfo#getMatchedResources()
     * @see UriInfo#getMatchedURIs()
     */
    public void addForMatched(Object resourceObject, String newUriPart) {
        if (resourceObject == null) {
            throw new IllegalArgumentException(
                    "The resource object must not be null");
        }
        if (newUriPart == null) {
            throw new IllegalArgumentException(
                    "The new URI part must not be null");
        }

        final StringBuilder newUri;
        if (this.matchedURIs.isEmpty())
            newUri = new StringBuilder();
        else
            newUri = new StringBuilder(this.matchedURIs.getFirst());
        if (newUriPart.length() == 0 || newUriPart.charAt(0) != '/') {
            newUri.append('/');
        }
        newUri.append(newUriPart);
        this.matchedResources.addFirst(resourceObject);
        this.matchedURIs.addFirst(newUri.toString());
    }

    /**
     * @param varName
     * @param varValue
     */
    public void addPathParamsEnc(String varName, String varValue) {
        checkChangeable();
        interalGetPathParamsEncoded().add(varName, varValue);
    }

    /**
     * Checks, if this object is changeable. If not, a
     * {@link IllegalStateException} is thrown.
     * 
     * @throws IllegalStateException
     */
    protected void checkChangeable() throws IllegalStateException {
        if (!isChangeable()) {
            throw new IllegalStateException(
                    "The CallContext is no longer changeable");
        }
    }

    private boolean checkIfOneMatch(List<Tag> requestETags, Tag entityTag) {
        if (entityTag.isWeak()) {
            return false;
        }
        for (final Tag requestETag : requestETags) {
            if (entityTag.equals(requestETag)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Creates an unmodifiable List of {@link PathSegment}s.
     * 
     * @param decode
     *                indicates, if the values should be decoded or not
     * @return
     */
    private List<PathSegment> createPathSegments(boolean decode) {
        List<String> segmentsEnc;
        segmentsEnc = this.referenceOriginal.getRelativeRef().getSegments();
        final int l = segmentsEnc.size();
        final List<PathSegment> pathSegments = new ArrayList<PathSegment>(l);
        for (int i = 0; i < l; i++) {
            final String segmentEnc = segmentsEnc.get(i);
            pathSegments.add(new JaxRsPathSegment(segmentEnc, decode, i));
        }
        return Collections.unmodifiableList(pathSegments);
    }

    /**
     * @param ref
     * @return
     * @throws IllegalArgumentException
     */
    private UriBuilder createUriBuilder(Reference ref) {
        // NICE what happens, if the Reference is invalid for the UriBuilder?
        final UriBuilder b = new JaxRsUriBuilder();
        b.scheme(ref.getScheme(false));
        b.userInfo(ref.getUserInfo(false));
        b.host(ref.getHostDomain(false));
        b.port(ref.getHostPort());
        b.path(ref.getPath(false));
        b.replaceQuery(ref.getQuery(false));
        b.fragment(ref.getFragment(false));
        return b;
    }

    @Override
    public boolean equals(Object anotherObject) {
        if (this == anotherObject) {
            return true;
        }
        if (!(anotherObject instanceof UriInfo)) {
            return false;
        }
        final UriInfo other = (UriInfo) anotherObject;
        if (!getBaseUri().equals(other.getBaseUri())) {
            return false;
        }
        if (!this.getPathSegments().equals(other.getPathSegments())) {
            return false;
        }
        if (!Util.equals(this.getPathParameters(), other.getPathParameters())) {
            return false;
        }
        return true;
    }

    /**
     * Evaluate request preconditions based on the passed in value.
     * 
     * @param lastModified
     *                a date that specifies the modification date of the
     *                resource
     * @return null if the preconditions are met or a ResponseBuilder set with
     *         the appropriate status if the preconditions are not met.
     * @throws java.lang.IllegalStateException
     *                 if called outside the scope of a request
     * 
     * @see javax.ws.rs.core.Request#evaluatePreconditions(java.util.Date)
     * @see #evaluatePreconditions(Date, EntityTag)
     */
    public ResponseBuilder evaluatePreconditions(Date lastModified) {
        return evaluatePreconditions(lastModified, null);
    }

    /**
     * Evaluate request preconditions based on the passed in value.
     * 
     * @param lastModified
     *                a date that specifies the modification date of the
     *                resource
     * @param entityTag
     *                an ETag for the current state of the resource
     * @return null if the preconditions are met or a ResponseBuilder set with
     *         the appropriate status if the preconditions are not met. A
     *         returned ResponseBuilder will include an ETag header set with the
     *         value of eTag.
     * @throws java.lang.IllegalStateException
     *                 if called outside the scope of a request
     * 
     * @see javax.ws.rs.core.Request#evaluatePreconditions(java.util.Date,
     *      javax.ws.rs.core.EntityTag)
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-10.3.5">RFC 2616, section 10.3.5: Status 304: Not Modified</a>
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-10.4.13">RFC 2616, section 10.4.13: Status 412: Precondition Failed</a>
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-13.3">RFC 2616, section 13.3: (Caching) Validation Model</a>
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-14.24">RFC 2616, section 14.24: Header "If-Match"</a>
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-14.25">RFC 2616, section 14.25: Header "If-Modified-Since"</a>
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-14.26">RFC 2616, section 14.26: Header "If-None-Match"</a>
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-14.28">RFC 2616, section 14.28: Header "If-Unmodified-Since"</a>
     */
    public ResponseBuilder evaluatePreconditions(Date lastModified,
            EntityTag entityTag) {
        // NICE Vorbed. werden mit Conditions.getStatus() unterstuetzt.
        if ((lastModified == null) && (entityTag == null)) {
            return null;
        }
        ResponseBuilder rb = null;
        final Method requestMethod = this.request.getMethod();
        final Conditions conditions = this.request.getConditions();
        if (lastModified != null) {
            // Header "If-Modified-Since"
            final Date modSinceCond = conditions.getModifiedSince();
            if (modSinceCond != null) {
                if (modSinceCond.after(lastModified)) {
                    // the Entity was not changed
                    final boolean readRequest = requestMethod
                            .equals(Method.GET)
                            || requestMethod.equals(Method.HEAD);
                    if (readRequest) {
                        rb = Response.notModified();
                        rb.lastModified(lastModified);
                        rb.tag(entityTag);
                    } else {
                        return precFailed("The entity was not modified since "
                                + Util.formatDate(modSinceCond, false));
                    }
                } else {
                    // entity was changed -> check for other precoditions
                }
            }
            // Header "If-Unmodified-Since"
            final Date unmodSinceCond = conditions.getUnmodifiedSince();
            if (unmodSinceCond != null) {
                if (unmodSinceCond.after(lastModified)) {
                    // entity was not changed -> Web Service must recalculate it
                    return null;
                } else {
                    // the Entity was changed
                    return precFailed("The entity was modified since "
                            + Util.formatDate(unmodSinceCond, false));
                }
            }
        }
        if (entityTag != null) {
            final Tag actualEntityTag = Converter.toRestletTag(entityTag);
            // Header "If-Match"
            final List<Tag> requestMatchETags = conditions.getMatch();
            if (!requestMatchETags.isEmpty()) {
                final boolean match = checkIfOneMatch(requestMatchETags,
                        actualEntityTag);
                if (!match) {
                    return precFailed("The entity does not match Entity Tag "
                            + entityTag);
                }
            } else {
                // default answer to the request
            }
            // Header "If-None-Match"
            final List<Tag> requestNoneMatchETags = conditions.getNoneMatch();
            if (!requestNoneMatchETags.isEmpty()) {
                final boolean match = checkIfOneMatch(requestNoneMatchETags,
                        actualEntityTag);
                if (match) {
                    return precFailed("The entity matches Entity Tag "
                            + entityTag);
                }
            } else {
                // default answer to the request
            }
        }
        return rb;
    }

    /**
     * Evaluate request preconditions based on the passed in value.
     * 
     * @param entityTag
     *                an ETag for the current state of the resource
     * @return null if the preconditions are met or a ResponseBuilder set with
     *         the appropriate status if the preconditions are not met. A
     *         returned ResponseBuilder will include an ETag header set with the
     *         value of eTag.
     * @throws java.lang.IllegalStateException
     *                 if called outside the scope of a request
     * @see javax.ws.rs.core.Request#evaluatePreconditions(javax.ws.rs.core.EntityTag)
     * @see #evaluatePreconditions(Date, EntityTag)
     */
    public ResponseBuilder evaluatePreconditions(EntityTag entityTag) {
        return evaluatePreconditions(null, entityTag);
    }

    /**
     * Get the absolute path of the request. This includes everything preceding
     * the path (host, port etc) but excludes query parameters and fragment.
     * This is a shortcut for
     * <code>uriInfo.getBase().resolve(uriInfo.getPath()).</code>
     * 
     * @return the absolute path of the request
     * @see UriInfo#getAbsolutePath()
     */
    public URI getAbsolutePath() {
        try {
            return new URI(this.referenceOriginal.toString(false, false));
        } catch (final URISyntaxException e) {
            throw wrapUriSyntaxExc(e, unexpectedLogger, "Could not create URI");
        }
    }

    /**
     * Get the absolute path of the request in the form of a UriBuilder. This
     * includes everything preceding the path (host, port etc) but excludes
     * query parameters and fragment.
     * 
     * @return a UriBuilder initialized with the absolute path of the request.
     * @see UriInfo#getAbsolutePathBuilder()
     */
    public UriBuilder getAbsolutePathBuilder() {
        return createUriBuilder(this.referenceOriginal);
    }

    /**
     * @see javax.ws.rs.core.HttpHeaders#getAcceptableLanguages()
     */
    public List<Locale> getAcceptableLanguages() {
        if (this.acceptedLanguages == null) {
            final SortedMetadata<Language> accLangages = SortedMetadata
                    .getForLanguages(this.request.getClientInfo()
                            .getAcceptedLanguages());
            final List<Locale> accLangs = new ArrayList<Locale>();
            for (final Language language : accLangages) {
                accLangs.add(Converter.toLocale(language));
            }
            this.acceptedLanguages = Collections.unmodifiableList(accLangs);
        }
        return this.acceptedLanguages;
    }

    /**
     * For use from JAX-RS interface.
     * 
     * @see HttpHeaders#getAcceptableMediaTypes()
     */
    public List<MediaType> getAcceptableMediaTypes() {
        if (this.acceptedMediaTypes == null) {
            final List<MediaType> accMediaTypes = new ArrayList<MediaType>();
            for (final org.restlet.data.MediaType mediaType : this.accMediaTypes) {
                accMediaTypes.add(Converter.toJaxRsMediaType(mediaType));
            }
            this.acceptedMediaTypes = Collections
                    .unmodifiableList(accMediaTypes);
        }
        return this.acceptedMediaTypes;
    }

    /**
     * Returns the accepted media types as Restlet
     * {@link org.restlet.data.MediaType}s.
     * 
     * @return the accepted {@link org.restlet.data.MediaType}s.
     */
    public SortedMetadata<org.restlet.data.MediaType> getAccMediaTypes() {
        return this.accMediaTypes;
    }

    /**
     * current state of the matchedResources
     * 
     * @see javax.ws.rs.core.UriInfo#getMatchedResources()
     */
    List<Object> getMatchedResources() {
        return this.matchedResources;
    }

    /**
     * current state of the matchedURIs
     * 
     * @see javax.ws.rs.core.UriInfo#getMatchedURIs()
     */
    List<String> getMatchedURIs() {
        return this.matchedURIs;
    }

    /**
     * Returns the string value of the authentication scheme used to protect the
     * resource. If the resource is not authenticated, null is returned.
     * 
     * Values are the same as the CGI variable AUTH_TYPE
     * 
     * @return one of the static members BASIC_AUTH, FORM_AUTH,
     *         CLIENT_CERT_AUTH, DIGEST_AUTH (suitable for == comparison) or the
     *         container-specific string indicating the authentication scheme,
     *         or null if the request was not authenticated.
     * @see SecurityContext#getAuthenticationScheme()
     */
    public String getAuthenticationScheme() {
        if (SecurityUtil.isSslClientCertAuth(this.request)) {
            return SecurityContext.CLIENT_CERT_AUTH;
        }
        ChallengeResponse challengeResponse = request.getChallengeResponse();
        if (challengeResponse == null) {
            return null;
        }
        if (!challengeResponse.isAuthenticated()) {
            return null;
        }
        final ChallengeScheme authScheme = challengeResponse.getScheme();
        if (authScheme == null) {
            return null;
        }
        if (authScheme.equals(ChallengeScheme.HTTP_BASIC)) {
            return SecurityContext.BASIC_AUTH;
        }
        if (authScheme.equals(ChallengeScheme.HTTP_DIGEST)) {
            return SecurityContext.DIGEST_AUTH;
        }
        // if (authScheme.equals(ChallengeScheme.HTTPS_CLIENT_CERT))
        // return SecurityContext.CLIENT_CERT_AUTH;
        // if (authScheme.equals(ChallengeScheme.HTTP_SERVLET_FORM))
        // return SecurityContext.FORM_AUTH;
        return authScheme.getName();
    }

    /**
     * Get the base URI of the application. URIs of resource beans are all
     * relative to this base URI.
     * 
     * @return the base URI of the application
     * @see UriInfo#getBaseUri()
     */
    public URI getBaseUri() {
        try {
            return new URI(getBaseUriStr());
        } catch (final URISyntaxException e) {
            throw wrapUriSyntaxExc(e, unexpectedLogger, "Could not create URI");
        }
    }

    /**
     * Get the absolute path of the request in the form of a UriBuilder. This
     * includes everything preceding the path (host, port etc) but excludes
     * query parameters and fragment.
     * 
     * @return a UriBuilder initialized with the absolute path of the request.
     * @see UriInfo#getAbsolutePathBuilder()
     * @see UriInfo#getBaseUriBuilder()
     */
    public UriBuilder getBaseUriBuilder() {
        return UriBuilder.fromUri(getBaseUriStr());
    }

    private String getBaseUriStr() {
        if (this.baseUri == null) {
            final Reference baseRef = this.referenceCut.getBaseRef();
            if (baseRef != null) {
                this.baseUri = baseRef.toString(false, false);
            }
        }
        return this.baseUri;
    }

    /**
     * Get the request URI extension. The returned string includes any
     * extensions remove during request pre-processing for the purposes of
     * URI-based content negotiation. E.g. if the request URI was:
     * 
     * <pre>
     * http://example.com/resource.xml.en
     * </pre>
     * 
     * this method would return "xml.en" even if an applications implementation
     * of {@link ApplicationConfig#getMediaTypeMappings()} returned a map that
     * included "xml" as a key
     * 
     * @return the request URI extension
     * @see javax.ws.rs.core.UriInfo#getConnegExtension()
     */
    public String getConnegExtension() {
        return referenceOriginal.getExtensions();
    }

    /**
     * Get any cookies that accompanied the request.
     * 
     * @return a map of cookie name (String) to Cookie.
     * @see HttpHeaders#getCookies()
     */
    public Map<String, Cookie> getCookies() {
        if (this.cookies == null) {
            final Map<String, Cookie> cookies = new HashMap<String, Cookie>();
            for (final org.restlet.data.Cookie rc : this.request.getCookies()) {
                final Cookie cookie = Converter.toJaxRsCookie(rc);
                cookies.put(cookie.getName(), cookie);
            }
            this.cookies = Collections.unmodifiableMap(cookies);
        }
        return this.cookies;
    }

    /**
     * @see HttpHeaders#getLanguage()
     */
    public Locale getLanguage() {
        if (this.language == null) {
            final Representation entity = this.request.getEntity();
            if (entity == null) {
                return null;
            }
            final List<Language> languages = entity.getLanguages();
            if (languages.isEmpty()) {
                return null;
            }
            this.language = Converter.toLocale(Util.getFirstElement(languages));
        }
        return this.language;
    }

    /**
     * Returns the last matrix parameter with the given name; leaves it encoded.
     * 
     * @param matrixParamAnnot
     * @return
     * @see #matrixParamEncIter(MatrixParam)
     */
    public String getLastMatrixParamEnc(MatrixParam matrixParamAnnot) {
        final String mpName = matrixParamAnnot.value();
        final List<PathSegment> pathSegments = getPathSegments(false);
        for (int i = pathSegments.size() - 1; i >= 0; i--) {
            final PathSegment pathSegment = pathSegments.get(i);
            final List<String> mpValues = pathSegment.getMatrixParameters()
                    .get(mpName);
            if ((mpValues != null) && !mpValues.isEmpty()) {
                final String result = Util.getLastElement(mpValues);
                if (result == null) {
                    return "";
                }
                return result;
            }
        }
        return null;
    }

    /**
     * @param annotation
     * @return
     * @see #pathParamEncIter(PathParam)
     */
    public String getLastPathParamEnc(PathParam annotation) {
        final String varName = annotation.value();
        final List<String> values = interalGetPathParamsEncoded().get(varName);
        if ((values == null) || values.isEmpty()) {
            return null;
        }
        return Util.getLastElement(values);
    }

    /**
     * @param pathParam
     * @return
     */
    public String getLastPathSegmentEnc(PathParam pathParam) {
        pathParam.annotationType();
        // TODO CallContext.getLastPathSegmentEnc(PathParam)
        throw new NotYetImplementedException();
    }

    /**
     * Get the media type of the request entity
     * 
     * @return the media type or null if there is no request entity.
     * @throws java.lang.IllegalStateException
     *                 if called outside the scope of a request
     * @see HttpHeaders#getMediaType()
     */
    public MediaType getMediaType() {
        if (this.mediaType == null) {
            org.restlet.data.MediaType rmt = request.getEntity().getMediaType();
            CharacterSet rCharSet = request.getEntity().getCharacterSet();
            this.mediaType = Converter.toJaxRsMediaType(rmt, rCharSet);
        }
        return this.mediaType;
    }

    /**
     * @see javax.ws.rs.core.Request#getMethod()
     */
    public String getMethod() {
        return this.request.getMethod().getName();
    }

    /**
     * Get the path of the current request relative to the base URI as a string.
     * All sequences of escaped octets are decoded, equivalent to
     * <code>getPath(true)</code>.
     * 
     * @return the relative URI path.
     * @see UriInfo#getPath()
     */
    public String getPath() {
        return getPath(true);
    }

    /**
     * Get the path of the current request relative to the base URI as a string.
     * 
     * @param decode
     *                controls whether sequences of escaped octets are decoded
     *                (true) or not (false).
     * @return the relative URI path.
     * @see UriInfo#getPath(boolean)
     */
    public String getPath(boolean decode) {
        final String path = this.referenceOriginal.getRelativeRef().toString(
                true, true);
        if (!decode) {
            return path;
        }
        return Reference.decode(path);
    }

    /**
     * Get the values of any embedded URI template parameters. All sequences of
     * escaped octets are decoded, equivalent to
     * <code>getTemplateParameters(true)</code>.
     * 
     * @return an unmodifiable map of parameter names and values
     * @throws java.lang.IllegalStateException
     *                 if called outside the scope of a request
     * @see javax.ws.rs.Path
     * @see UriInfo#getPathParameters()
     */
    public MultivaluedMap<String, String> getPathParameters() {
        if (this.pathParametersDecoded == null) {
            final MultivaluedMapImpl<String, String> pathParamsDec = new MultivaluedMapImpl<String, String>();
            for (final Map.Entry<String, List<String>> entryEnc : interalGetPathParamsEncoded()
                    .entrySet()) {
                final String keyDec = Reference.decode(entryEnc.getKey());
                final List<String> valuesEnc = entryEnc.getValue();
                List<String> valuesDec = new ArrayList<String>(valuesEnc.size());
                for (final String valueEnc : valuesEnc) {
                    valuesDec.add(Reference.decode(valueEnc));
                }
                pathParamsDec.put(keyDec, valuesDec);
            }
            UnmodifiableMultivaluedMap<String, String> ppd;
            ppd = UnmodifiableMultivaluedMap.get(pathParamsDec, false);
            if (isChangeable()) {
                return ppd;
            }
            this.pathParametersDecoded = ppd;
        }
        return this.pathParametersDecoded;
    }

    /**
     * Get the values of any embedded URI template parameters.
     * 
     * @param decode
     *                controls whether sequences of escaped octets are decoded
     *                (true) or not (false).
     * @return an unmodifiable map of parameter names and values
     * @throws java.lang.IllegalStateException
     *                 if called outside the scope of a request
     * @see javax.ws.rs.Path
     * @see UriInfo#getPathParameters(boolean)
     */
    public MultivaluedMap<String, String> getPathParameters(boolean decode) {
        if (decode) {
            return getPathParameters();
        } else {
            return UnmodifiableMultivaluedMap
                    .get(interalGetPathParamsEncoded());
        }
    }

    /**
     * Get the path of the current request relative to the base URI as a list of
     * {@link PathSegment}. This method is useful when the path needs to be
     * parsed, particularly when matrix parameters may be present in the path.
     * All sequences of escaped octets are decoded, equivalent to
     * <code>getPathSegments(true)</code>.
     * 
     * @return an unmodifiable list of {@link PathSegment}. The matrix
     *         parameter map of each path segment is also unmodifiable.
     * @throws java.lang.IllegalStateException
     *                 if called outside the scope of a request
     * @see PathSegment
     * @see UriInfo#getPathSegments()
     */
    public List<PathSegment> getPathSegments() {
        return getPathSegments(true);
    }

    /**
     * Get the path of the current request relative to the base URI as a list of
     * {@link PathSegment}. This method is useful when the path needs to be
     * parsed, particularly when matrix parameters may be present in the path.
     * 
     * @param decode
     *                controls whether sequences of escaped octets are decoded
     *                (true) or not (false).
     * @return an unmodifiable list of {@link PathSegment}. The matrix
     *         parameter map of each path segment is also unmodifiable.
     * @throws java.lang.IllegalStateException
     *                 if called outside the scope of a request
     * @see PathSegment
     * @see UriInfo#getPathSegments(boolean)
     */
    public List<PathSegment> getPathSegments(boolean decode) {
        if (decode) {
            if (this.pathSegmentsDecoded == null) {
                this.pathSegmentsDecoded = createPathSegments(true);
            }
            return this.pathSegmentsDecoded;
        } else {
            if (this.pathSegmentsEncoded == null) {
                this.pathSegmentsEncoded = createPathSegments(false);
            }
            return this.pathSegmentsEncoded;
        }
    }

    /**
     * Get the URI query parameters of the current request. All sequences of
     * escaped octets are decoded, equivalent to
     * <code>getQueryParameters(true)</code>.
     * 
     * @return an unmodifiable map of query parameter names and values
     * @throws java.lang.IllegalStateException
     *                 if called outside the scope of a request
     * @see UriInfo#getQueryParameters()
     */
    public MultivaluedMap<String, String> getQueryParameters() {
        if (this.queryParametersDecoded == null) {
            this.queryParametersDecoded = UnmodifiableMultivaluedMap
                    .getFromForm(this.referenceOriginal.getQueryAsForm(), false);
        }
        return this.queryParametersDecoded;
    }

    /**
     * Get the URI query parameters of the current request.
     * 
     * @param decode
     *                controls whether sequences of escaped octets in parameter
     *                names and values are decoded (true) or not (false).
     * @return an unmodifiable map of query parameter names and values
     * @throws java.lang.IllegalStateException
     *                 if called outside the scope of a request
     * @see UriInfo#getQueryParameters(boolean)
     */
    public MultivaluedMap<String, String> getQueryParameters(boolean decode) {
        if (decode) {
            return getQueryParameters();
        }
        if (this.queryParametersEncoded == null) {
            final Form queryForm = Converter
                    .toFormEncoded(this.referenceOriginal.getQuery());
            this.queryParametersEncoded = UnmodifiableMultivaluedMap
                    .getFromForm(queryForm, false);
        }
        return this.queryParametersEncoded;
    }

    /**
     * Returns the Restlet {@link org.restlet.data.Request}
     * 
     * @return the Restlet {@link org.restlet.data.Request}
     */
    public Request getRequest() {
        return this.request;
    }

    /**
     * @see javax.ws.rs.core.HttpHeaders#getRequestHeader(java.lang.String)
     */
    public List<String> getRequestHeader(String headerName) {
        String[] values;
        values = Util.getHttpHeaders(this.request).getValuesArray(headerName);
        return Collections.unmodifiableList(Arrays.asList(values));
    }

    /**
     * @see HttpHeaders#getRequestHeaders()
     */
    public MultivaluedMap<String, String> getRequestHeaders() {
        if (this.requestHeaders == null) {
            this.requestHeaders = UnmodifiableMultivaluedMap.getFromForm(Util
                    .getHttpHeaders(this.request), false);
        }
        return this.requestHeaders;
    }

    /**
     * @return the absolute request URI
     * @see UriInfo#getRequestUri()
     */
    public URI getRequestUri() {
        try {
            return new URI(this.referenceOriginal.toString(true, true));
        } catch (final URISyntaxException e) {
            throw wrapUriSyntaxExc(e, unexpectedLogger, "Could not create URI");
        }
    }

    /**
     * Get the absolute request URI in the form of a UriBuilder.
     * 
     * @return a UriBuilder initialized with the absolute request URI.
     * @see UriInfo#getRequestUriBuilder()
     */
    public UriBuilder getRequestUriBuilder() {
        return UriBuilder.fromUri(getRequestUri());
    }

    /**
     * Returns the Restlet {@link org.restlet.data.Response}
     * 
     * @return the Restlet {@link org.restlet.data.Response}
     */
    public org.restlet.data.Response getResponse() {
        return this.response;
    }

    /**
     * Returns a <code>java.security.Principal</code> object containing the
     * name of the current authenticated user. If the user has not been
     * authenticated, the method returns null.
     * 
     * @return a <code>java.security.Principal</code> containing the name of
     *         the user making this request; null if the user has not been
     *         authenticated
     * @see SecurityContext#getUserPrincipal()
     */
    public Principal getUserPrincipal() {
        if (this.request.getChallengeResponse() != null) {
            return this.request.getChallengeResponse().getPrincipal();
        }
        return SecurityUtil.getSslClientCertPrincipal(this.request);
    }

    @Override
    public int hashCode() {
        return this.getBaseUriStr().hashCode()
                ^ this.getPathSegments().hashCode()
                ^ this.getPathParameters().hashCode();
    }

    /**
     * @return the pathParametersEncoded
     */
    protected MultivaluedMap<String, String> interalGetPathParamsEncoded() {
        if (this.pathParametersEncoded == null) {
            this.pathParametersEncoded = new MultivaluedMapImpl<String, String>();
        }
        return this.pathParametersEncoded;
    }

    protected boolean isChangeable() {
        return !this.readOnly;
    }

    /**
     * Returns a boolean indicating whether this request was made using a secure
     * channel, such as HTTPS.
     * 
     * @return <code>true</code> if the request was made using a secure
     *         channel, <code>false</code> otherwise
     * @see SecurityContext#isSecure()
     */
    public boolean isSecure() {
        return this.request.isConfidential();
    }

    /**
     * Returns a boolean indicating whether the authenticated user is included
     * in the specified logical "role". If the user has not been authenticated,
     * the method returns <code>false</code>.
     * 
     * @param role
     *                a <code>String</code> specifying the name of the role
     * @return a <code>boolean</code> indicating whether the user making the
     *         request belongs to a given role; <code>false</code> if the user
     *         has not been authenticated
     * @see SecurityContext#isUserInRole(String)
     */
    public boolean isUserInRole(String role) {
        // LATER here ServletRequest.isUserInRole(role)
        final Principal principal = (this.request.getChallengeResponse() == null) ? null
                : this.request.getChallengeResponse().getPrincipal();
        return this.roleChecker.isInRole(principal, role);
    }

    /**
     * @param matrixParamAnnot
     * @return
     * @see #getLastMatrixParamEnc(MatrixParam)
     */
    public Iterator<String> matrixParamEncIter(MatrixParam matrixParamAnnot) {
        final String mpName = matrixParamAnnot.value();
        return new MatrixParamEncIter(mpName, getPathSegments(false));
    }

    /**
     * @param pathParamAnnot
     * @return
     * @see #getLastPathParamEnc(PathParam)
     */
    public Iterator<String> pathParamEncIter(PathParam pathParamAnnot) {
        // LATER perhaps this method could be removed, if it is not needed for
        // @PathParam(..) PathSegment
        final String ppName = pathParamAnnot.value();
        List<String> pathParamValues;
        pathParamValues = interalGetPathParamsEncoded().get(ppName);
        if (pathParamValues == null) {
            return EmptyIterator.get();
        }
        return pathParamValues.iterator();
    }

    /**
     * @param pathParam
     * @return
     */
    public Iterator<String> pathSegementEncIter(PathParam pathParam) {
        pathParam.annotationType();
        // TODO CallContext.pathSegementEncIter(PathParam)
        throw new NotYetImplementedException();
    }

    /**
     * Creates a response with status 412 (Precondition Failed).
     * 
     * @param entityMessage
     *                Plain Text error message. Will be returned as entity.
     * @return Returns a response with status 412 (Precondition Failed) and the
     *         given message as entity.
     */
    private ResponseBuilder precFailed(String entityMessage) {
        final ResponseBuilder rb = Response.status(STATUS_PREC_FAILED);
        rb.entity(entityMessage);
        rb.language(Language.ENGLISH.getName());
        rb.type(Converter.toJaxRsMediaType(
                org.restlet.data.MediaType.TEXT_PLAIN, null));
        return rb;
    }

    /**
     * Select the representation variant that best matches the request. More
     * explicit variants are chosen ahead of less explicit ones. A vary header
     * is computed from the supplied list and automatically added to the
     * response.
     * 
     * @param variants
     *                a list of Variant that describe all of the available
     *                representation variants.
     * @return the variant that best matches the request.
     * @see Variant.VariantListBuilder
     * @throws IllegalArgumentException
     *                 if variants is null or empty.
     * @see javax.ws.rs.core.Request#selectVariant(List)
     */
    public Variant selectVariant(List<Variant> variants)
            throws IllegalArgumentException {
        if ((variants == null) || variants.isEmpty()) {
            throw new IllegalArgumentException();
        }
        final List<org.restlet.resource.Variant> restletVariants = Converter
                .toRestletVariants(variants);
        final org.restlet.resource.Variant bestRestlVar = this.request
                .getClientInfo().getPreferredVariant(restletVariants, null);
        final Variant bestVariant = Converter.toJaxRsVariant(bestRestlVar);
        final Set<Dimension> dimensions = this.response.getDimensions();
        if (bestRestlVar.getCharacterSet() != null) {
            dimensions.add(Dimension.CHARACTER_SET);
        }
        if (bestRestlVar.getEncodings() != null) {
            dimensions.add(Dimension.ENCODING);
        }
        if (bestRestlVar.getLanguages() != null) {
            dimensions.add(Dimension.LANGUAGE);
        }
        if (bestRestlVar.getMediaType() != null) {
            dimensions.add(Dimension.MEDIA_TYPE);
        }
        // NICE add also to JAX-RS-Response, which is possibly not yet
        // generated.
        return bestVariant;
    }

    /**
     * Sets the Context to be read only. As from now changes are not allowed.
     * This method is intended to be used by {@link CallContext#setReadOnly()}.
     * Ignored by {@link #addForMatched(Object, String)}.
     */
    public void setReadOnly() {
        this.readOnly = true;
    }

    @Override
    public String toString() {
        return this.referenceOriginal.toString(true, false);
    }

    /**
     * This method throws an {@link WebApplicationException} for Exceptions
     * where is no planned handling. Logs the exception (warn {@link Level}).
     * 
     * @param exc
     *                the catched URISyntaxException
     * @param unexpectedLogger
     *                the unexpectedLogger to log the messade
     * @param logMessage
     *                the message to log.
     * @return Will never return anything, because the generated
     *         WebApplicationException will be thrown. You an formally throw the
     *         returned exception (e.g. in a catch block). So the compiler is
     *         sure, that the method will be left here.
     * @throws WebApplicationException
     *                 contains the given {@link Exception}
     */
    private WebApplicationException wrapUriSyntaxExc(URISyntaxException exc,
            Logger logger, String logMessage) throws WebApplicationException {
        logger.log(Level.WARNING, logMessage, exc);
        exc.printStackTrace();
        throw new WebApplicationException(exc,
                javax.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR);
    }
}
