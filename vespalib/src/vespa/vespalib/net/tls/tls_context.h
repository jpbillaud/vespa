// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#pragma once

#include <memory>

namespace vespalib::net::tls {

class TransportSecurityOptions;
class CertificateVerificationCallback;

struct TlsContext {
    virtual ~TlsContext() = default;

    // Create a TLS context which verifies certificates according to the provided options'
    // CA trust roots AND allowed peer policies
    static std::shared_ptr<TlsContext> create_default_context(const TransportSecurityOptions&);
    // Create a TLS context where the certificate verification callback is explicitly provided.
    // IMPORTANT: This does NOT verify that the peer satisfies the allowed peer policies!
    //            It only verifies that a peer is signed by a trusted CA. This function should
    //            therefore only be used in very special circumstances, such as unit tests.
    static std::shared_ptr<TlsContext> create_default_context(
            const TransportSecurityOptions&,
            std::shared_ptr<CertificateVerificationCallback> cert_verify_callback);
};

}
