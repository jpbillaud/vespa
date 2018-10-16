// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include "openssl_typedefs.h"
#include "openssl_tls_context_impl.h"
#include <vespa/vespalib/net/tls/crypto_exception.h>
#include <vespa/vespalib/net/tls/transport_security_options.h>
#include <vespa/vespalib/util/stringfmt.h>
#include <mutex>
#include <vector>
#include <memory>
#include <stdexcept>
#include <openssl/ssl.h>
#include <openssl/crypto.h>
#include <openssl/err.h>
#include <openssl/x509v3.h>
#include <openssl/asn1.h>
#include <openssl/pem.h>

#include <vespa/log/log.h>
LOG_SETUP(".vespalib.net.tls.openssl_tls_context_impl");

#if (OPENSSL_VERSION_NUMBER < 0x10000000L)
// < 1.0 requires explicit thread ID callback support.
#  error "Provided OpenSSL version is too darn old, need at least 1.0.0"
#endif

namespace vespalib::net::tls::impl {

namespace {

#if (OPENSSL_VERSION_NUMBER < 0x10100000L)

std::vector<std::unique_ptr<std::mutex>> _g_mutexes;

// Some words on OpenSSL legacy locking: OpenSSL does not implement locking
// itself internally, deferring to user code callbacks that Do The Needful(tm).
// The `n` parameter refers to the nth mutex, which is always < CRYPTO_num_locks().
void openssl_locking_cb(int mode, int n, [[maybe_unused]] const char *file, [[maybe_unused]] int line) {
    if (mode & CRYPTO_LOCK) {
        _g_mutexes[n]->lock();
    } else {
        _g_mutexes[n]->unlock();
    }
}

#endif

struct OpenSslLibraryResources {
    OpenSslLibraryResources();
    ~OpenSslLibraryResources();
};

OpenSslLibraryResources::OpenSslLibraryResources() {
    // Other implementations (Asio, gRPC) disagree on whether main library init
    // itself should take place on >= v1.1. We always do it to be on the safe side..!
    ::SSL_library_init();
    ::SSL_load_error_strings();
    ::OpenSSL_add_all_algorithms();
    // Luckily, the mutex callback madness is not present on >= v1.1
#if (OPENSSL_VERSION_NUMBER < 0x10100000L)
    // Since the init path should happen only once globally, but multiple libraries
    // may use OpenSSL, make sure we don't step on any toes if locking callbacks are
    // already set up.
    if (!::CRYPTO_get_locking_callback()) {
        const int num_locks = ::CRYPTO_num_locks();
        LOG_ASSERT(num_locks > 0);
        _g_mutexes.reserve(static_cast<size_t>(num_locks));
        for (int i = 0; i < num_locks; ++i) {
            _g_mutexes.emplace_back(std::make_unique<std::mutex>());
        }
        ::CRYPTO_set_locking_callback(openssl_locking_cb);
    }
#endif
}

OpenSslLibraryResources::~OpenSslLibraryResources() {
#if (OPENSSL_VERSION_NUMBER < 0x10100000L)
    if (::CRYPTO_get_locking_callback() == openssl_locking_cb) {
        ::CRYPTO_set_locking_callback(nullptr);
    }
#endif
    ERR_free_strings();
    EVP_cleanup();
    CRYPTO_cleanup_all_ex_data();
}

// TODO make global init instead..?
void ensure_openssl_initialized_once() {
    static OpenSslLibraryResources openssl_resources;
    (void) openssl_resources;
}

BioPtr bio_from_string(vespalib::stringref str) {
    LOG_ASSERT(str.size() <= INT_MAX);
#if (OPENSSL_VERSION_NUMBER >= 0x10002000L)
    BioPtr bio(::BIO_new_mem_buf(str.data(), static_cast<int>(str.size())));
#else
    BioPtr bio(::BIO_new_mem_buf(const_cast<char*>(str.data()), static_cast<int>(str.size())));
#endif
    if (!bio) {
        throw CryptoException("BIO_new_mem_buf");
    }
    return bio;
}

bool has_pem_eof_on_stack() {
    const auto err = ::ERR_peek_last_error();
    if (!err) {
        return false;
    }
    return ((ERR_GET_LIB(err) == ERR_LIB_PEM)
            && (ERR_GET_REASON(err) == PEM_R_NO_START_LINE));
}

vespalib::string ssl_error_from_stack() {
    char buf[256];
    ::ERR_error_string_n(::ERR_get_error(), buf, sizeof(buf));
    return vespalib::string(buf);
}

// Several OpenSSL functions take a magical user passphrase argument with
// potentially horrible default behavior for password protected input.
//
// From OpenSSL docs (https://www.openssl.org/docs/man1.1.0/crypto/PEM_read_bio_PrivateKey.html):
//
// "If the cb parameters is set to NULL and the u parameter is not NULL
//  then the u parameter is interpreted as a null terminated string to use
//  as the passphrase. If both cb and u are NULL then the default callback
//  routine is used which will typically prompt for the passphrase on the
//  current terminal with echoing turned off."
//
// Neat!
//
// Bonus points for being non-const as well.
constexpr inline void* empty_passphrase() {
    return const_cast<void*>(static_cast<const void*>(""));
}

void verify_pem_ok_or_eof(::X509* x509) {
    // It's OK if we don't have an X509 cert returned iff we failed to find
    // something that looks like the start of a PEM entry. This is to catch
    // cases where the PEM itself is malformed, since the X509 read routines
    // just return either nullptr or a cert object, making it hard to debug.
    if (!x509 && !has_pem_eof_on_stack()) {
        throw CryptoException(make_string("Failed to add X509 certificate from PEM: %s",
                                          ssl_error_from_stack().c_str()));
    }
}

// Attempt to read a PEM encoded (trusted) certificate from the given BIO.
// BIO might contain further certificates if function returns non-nullptr.
// Returns nullptr if no certificate could be loaded. This is usually an error,
// as this should be the first certificate in the chain.
X509Ptr read_trusted_x509_from_bio(::BIO& bio) {
    ::ERR_clear_error();
    // "_AUX" means the certificate is trusted. Why they couldn't name this function
    // something with "trusted" instead is left as an exercise to the reader.
    X509Ptr x509(::PEM_read_bio_X509_AUX(&bio, nullptr, nullptr, empty_passphrase()));
    verify_pem_ok_or_eof(x509.get());
    return x509;
}

// Attempt to read a PEM encoded certificate from the given BIO.
// BIO might contain further certificates if function returns non-nullptr.
// Returns nullptr if no certificate could be loaded. This usually implies
// that there are no more certificates left in the chain.
X509Ptr read_untrusted_x509_from_bio(::BIO& bio) {
    ::ERR_clear_error();
    X509Ptr x509(::PEM_read_bio_X509(&bio, nullptr, nullptr, empty_passphrase()));
    verify_pem_ok_or_eof(x509.get());
    return x509;
}

SslCtxPtr new_tls_ctx_with_auto_init() {
    ensure_openssl_initialized_once();
#if (OPENSSL_VERSION_NUMBER < 0x10100000L)
    return SslCtxPtr(::SSL_CTX_new(::TLSv1_2_method()));
#else
    SslCtxPtr ctx(::SSL_CTX_new(::TLS_method()));
    if (!::SSL_CTX_set_min_proto_version(ctx.get(), TLS1_2_VERSION)) {
        throw CryptoException("SSL_CTX_set_min_proto_version");
    }
    return ctx;
#endif
}

} // anon ns

OpenSslTlsContextImpl::OpenSslTlsContextImpl(const TransportSecurityOptions& ts_opts)
    : _ctx(new_tls_ctx_with_auto_init()),
      _cert_verify_callback(ts_opts.cert_verify_callback())
{
    if (!_ctx) {
        throw CryptoException("Failed to create new TLS context");
    }
    LOG_ASSERT(_cert_verify_callback.get() != nullptr);
    add_certificate_authorities(ts_opts.ca_certs_pem());
    if (!ts_opts.cert_chain_pem().empty() && !ts_opts.private_key_pem().empty()) {
        add_certificate_chain(ts_opts.cert_chain_pem());
        use_private_key(ts_opts.private_key_pem());
        verify_private_key();
    }
    enable_ephemeral_key_exchange();
    disable_compression();
    enforce_peer_certificate_verification();
    set_provided_certificate_verification_callback();
    // TODO set accepted cipher suites!
    // TODO `--> If not set in options, use Modern spec from https://wiki.mozilla.org/Security/Server_Side_TLS
}

OpenSslTlsContextImpl::~OpenSslTlsContextImpl() {
    void* cb_data = SSL_CTX_get_app_data(_ctx.get());
    if (cb_data) {
        // Referenced callback is kept in a shared_ptr, so lifetime is ensured.
        // Either way, clean up after ourselves.
        SSL_CTX_set_app_data(_ctx.get(), nullptr);
    }
}

void OpenSslTlsContextImpl::add_certificate_authorities(vespalib::stringref ca_pem) {
    auto bio = bio_from_string(ca_pem);
    ::X509_STORE* cert_store = ::SSL_CTX_get_cert_store(_ctx.get()); // Internal pointer, not owned by us.
    while (true) {
        auto ca_cert = read_untrusted_x509_from_bio(*bio);
        if (!ca_cert) {
            break;
        }
        if (::X509_STORE_add_cert(cert_store, ca_cert.get()) != 1) { // Does _not_ take ownership
            throw CryptoException("X509_STORE_add_cert");
        }
    }
}

void OpenSslTlsContextImpl::add_certificate_chain(vespalib::stringref chain_pem) {
    auto bio = bio_from_string(chain_pem);
    // First certificate in the chain is the node's own (trusted) certificate.
    auto own_cert = read_trusted_x509_from_bio(*bio);
    if (!own_cert) {
        throw CryptoException("No X509 certificates could be found in provided chain");
    }
    // Ownership of certificate is _not_ transferred, OpenSSL makes internal copy.
    // This is not well documented, but is mentioned by other impls.
    if (::SSL_CTX_use_certificate(_ctx.get(), own_cert.get()) != 1) {
        throw CryptoException("SSL_CTX_use_certificate");
    }
    // After the node's own certificate comes any intermediate CA-provided certificates.
    while (true) {
        auto ca_cert = read_untrusted_x509_from_bio(*bio);
        if (!ca_cert) {
            break; // No more certificates in chain, hooray!
        }
        // Ownership of certificate _is_ transferred here!
        if (!::SSL_CTX_add_extra_chain_cert(_ctx.get(), ca_cert.release())) {
            throw CryptoException("SSL_CTX_add_extra_chain_cert");
        }
    }
}

void OpenSslTlsContextImpl::use_private_key(vespalib::stringref key_pem) {
    auto bio = bio_from_string(key_pem);
    EvpPkeyPtr key(::PEM_read_bio_PrivateKey(bio.get(), nullptr, nullptr, empty_passphrase()));
    if (!key) {
        throw CryptoException("Failed to read PEM private key data");
    }
    // Ownership _not_ taken.
    if (::SSL_CTX_use_PrivateKey(_ctx.get(), key.get()) != 1) {
        throw CryptoException("SSL_CTX_use_PrivateKey");
    }
}

void OpenSslTlsContextImpl::verify_private_key() {
    if (::SSL_CTX_check_private_key(_ctx.get()) != 1) {
        throw CryptoException("SSL_CTX_check_private_key failed; mismatch between public and private key?");
    }
}

void OpenSslTlsContextImpl::enable_ephemeral_key_exchange() {
#if (OPENSSL_VERSION_NUMBER < 0x10100000L)
#  if (OPENSSL_VERSION_NUMBER >= 0x10002000L)
    // Always enabled by default on higher versions.
    // Auto curve selection is preferred over using SSL_CTX_set_ecdh_tmp
    if (!::SSL_CTX_set_ecdh_auto(_ctx.get(), 1)) {
        throw CryptoException("SSL_CTX_set_ecdh_auto");
    }
    // New ECDH key per connection.
    SSL_CTX_set_options(_ctx.get(), SSL_OP_SINGLE_ECDH_USE);
#  else
    // Set explicit P-256 curve used for ECDH purposes.
    EcKeyPtr ec_curve(::EC_KEY_new_by_curve_name(NID_X9_62_prime256v1));
    if (!ec_curve) {
        throw CryptoException("EC_KEY_new_by_curve_name(NID_X9_62_prime256v1)");
    }
    if (!::SSL_CTX_set_tmp_ecdh(_ctx.get(), ec_curve.get())) {
        throw CryptoException("SSL_CTX_set_tmp_ecdh");
    }
#  endif
#endif
}

void OpenSslTlsContextImpl::disable_compression() {
    // TLS stream compression is vulnerable to a host of chosen plaintext
    // attacks (CRIME, BREACH etc), so disable it.
    SSL_CTX_set_options(_ctx.get(), SSL_OP_NO_COMPRESSION);
}

namespace {

// There's no good reason for entries to contain embedded nulls, aside from
// trying to be sneaky. See Moxie Marlinspike's Blackhat USA 2009 presentation
// for context.
bool has_embedded_nulls(const char* data, size_t size) {
    return (memchr(data, '\0', size) != nullptr);
}

// Normally there should only be 1 CN entry in a certificate, but it's possible
// to specify multiple. We'll only report the last occurring one.
bool fill_certificate_common_name(::X509* cert, PeerCredentials& creds) {
    // We're only after CN entries of the subject name
    ::X509_NAME* subj_name = ::X509_get_subject_name(cert); // _not_ owned by us, never nullptr
    int pos = -1;
    // X509_NAME_get_index_by_NID returns -1 if there are no further indices containing
    // an entry with the given NID _after_ pos. -1 must be passed as the initial pos value,
    // since index 0 might be valid.
    while ((pos = ::X509_NAME_get_index_by_NID(subj_name, NID_commonName, pos)) >= 0) {
        ::X509_NAME_ENTRY* entry = ::X509_NAME_get_entry(subj_name, pos);
        if (!entry) {
            LOG(error, "Got X509 peer certificate with invalid CN entry");
            return false;
        }
        ::ASN1_STRING* cn_asn1 = ::X509_NAME_ENTRY_get_data(entry);
        if ((cn_asn1 != nullptr) && (cn_asn1->data != nullptr) && (cn_asn1->length > 0)) {
            const auto* data = reinterpret_cast<const char*>(cn_asn1->data);
            const auto size  = static_cast<size_t>(cn_asn1->length);
            if (has_embedded_nulls(data, size)) {
                LOG(warning, "Got X509 peer certificate with embedded nulls in CN field");
                return false;
            }
            creds.common_name.assign(data, size);
        }
    }
    return true;
}

struct GeneralNamesDeleter {
    void operator()(::GENERAL_NAMES* names) {
        ::GENERAL_NAMES_free(names);
    }
};

using GeneralNamesPtr = std::unique_ptr<::GENERAL_NAMES, GeneralNamesDeleter>;

bool fill_certificate_subject_alternate_names(::X509* cert, PeerCredentials& creds) {
    GeneralNamesPtr san_names(static_cast<GENERAL_NAMES*>(
            ::X509_get_ext_d2i(cert, NID_subject_alt_name, nullptr, nullptr)));
    if (san_names) {
        for (int i = 0; i < sk_GENERAL_NAME_num(san_names.get()); ++i) {
            auto* value = sk_GENERAL_NAME_value(san_names.get(), i);
            if (value->type == GEN_DNS) {
                auto* dns_name = value->d.dNSName; // const or non-const depending on version...
                if ((dns_name->type == V_ASN1_IA5STRING) && (dns_name->data != nullptr) && (dns_name->length > 0)) {
#if (OPENSSL_VERSION_NUMBER >= 0x10100000L)
                    const char* data  = reinterpret_cast<const char*>(::ASN1_STRING_get0_data(dns_name));
#else
                    const char* data  = reinterpret_cast<const char*>(::ASN1_STRING_data(dns_name));
#endif
                    const auto length = static_cast<size_t>(::ASN1_STRING_length(dns_name));
                    if (has_embedded_nulls(data, length)) {
                        LOG(warning, "Got X509 peer certificate with embedded nulls in SAN field");
                        return false;
                    }
                    creds.dns_sans.emplace_back(data, length);
                }
            } // TODO support GEN_IPADD SAN?
        }
    }
    return true;
}

// TODO if/when we want to move per-connection peer credentials into the crypto codec/socket
// itself, we probably need to set the verification callback (data) on _SSL_, not _SSL_CTX_..!
// Note: we try to be as conservative as possible. If anything looks out of place, we fail
// secure by denying the connection.
//
// References:
// https://github.com/boostorg/asio/blob/develop/include/boost/asio/ssl/impl/context.ipp
// https://github.com/boostorg/asio/blob/develop/include/boost/asio/ssl/impl/rfc2818_verification.ipp
int verify_cb_wrapper(int preverified_ok, ::X509_STORE_CTX* store_ctx) {
    if (!preverified_ok) {
        return 0; // If it's already known to be broken, we won't do anything more.
    }
    // The verify callback is invoked with every certificate in the chain, starting
    // with a root CA, then any intermediate CAs, then finally the peer's own certificate
    // at depth 0. We currently aren't interested in anything except the peer cert
    // since we trust the intermediates to have done their job.
    const bool is_peer_cert = (::X509_STORE_CTX_get_error_depth(store_ctx) == 0);
    if (!is_peer_cert) {
        return 1; // OK for root/intermediate cert.
    }
    // Fetch the SSL instance associated with the X509_STORE_CTX
    const void* data = ::X509_STORE_CTX_get_ex_data(store_ctx, ::SSL_get_ex_data_X509_STORE_CTX_idx());
    if (!data) {
        return 0;
    }
    const auto* ssl = static_cast<const ::SSL*>(data);
    const ::SSL_CTX* ssl_ctx = ::SSL_get_SSL_CTX(ssl);
    if (!ssl_ctx) {
        return 0;
    }
    auto* cert_validator = static_cast<CertificateVerificationCallback*>(SSL_CTX_get_app_data(ssl_ctx));
    if (!cert_validator) {
        return 0;
    }
    ::X509* cert = ::X509_STORE_CTX_get_current_cert(store_ctx); // _not_ owned by us
    if (!cert) {
        LOG(error, "Got X509_STORE_CTX with preverified_ok == 1 but no current cert");
        return 0;
    }
    PeerCredentials creds;
    if (!fill_certificate_common_name(cert, creds)) {
        return 0;
    }
    if (!fill_certificate_subject_alternate_names(cert, creds)) {
        return 0;
    }
    try {
        const bool verified_by_cb = cert_validator->verify(creds);
        if (!verified_by_cb) {
            LOG(debug, "Connection rejected by certificate verification callback");
            return 0;
        }
    } catch (std::exception& e) {
        LOG(error, "Got exception during certificate verification callback: %s", e.what());
        return 0;
    } // we don't expect any non-std::exception derived exceptions, so let them terminate the process.
    return 1;
}

} // anon ns

void OpenSslTlsContextImpl::enforce_peer_certificate_verification() {
    // We require full mutual certificate verification. No way to configure
    // out of this, at least not for the time being.
    ::SSL_CTX_set_verify(_ctx.get(), SSL_VERIFY_PEER | SSL_VERIFY_FAIL_IF_NO_PEER_CERT, verify_cb_wrapper);
}

void OpenSslTlsContextImpl::set_provided_certificate_verification_callback() {
    SSL_CTX_set_app_data(_ctx.get(), _cert_verify_callback.get());
}

}
