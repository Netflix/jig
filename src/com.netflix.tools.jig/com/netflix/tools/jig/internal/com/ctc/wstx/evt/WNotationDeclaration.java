package com.netflix.tools.jig.internal.com.ctc.wstx.evt;

import java.net.URL;

import javax.xml.stream.Location;

import com.netflix.tools.jig.internal.org.codehaus.stax2.ri.evt.NotationDeclarationEventImpl;

/**
 * Woodstox implementation of {@link com.netflix.tools.jig.internal.org.codehaus.stax2.evt.NotationDeclaration2}.
 * The only required addition is that of passing in the Base URI.
 *
 * @author Tatu Saloranta
 *
 * @since 4.0.0
 */
public class WNotationDeclaration
    extends NotationDeclarationEventImpl
{
    /**
     * Base URL that can be used to resolve the notation reference if
     * necessary.
     */
    final URL _baseURL;

    public WNotationDeclaration(Location loc,
                                String name, String pubId, String sysId,
                                URL baseURL)
    {
        super(loc, name, pubId, sysId);
        _baseURL = baseURL;
    }

    @Override
    public String getBaseURI()
    {
        if (_baseURL == null) {
            return super.getBaseURI();
        }
        return _baseURL.toExternalForm();
    }
}
