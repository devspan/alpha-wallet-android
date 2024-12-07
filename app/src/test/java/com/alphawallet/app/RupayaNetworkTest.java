package com.alphawallet.app;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsEqual.equalTo;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.alphawallet.ethereum.EthereumNetworkBase;
import com.alphawallet.ethereum.NetworkInfo;
import com.alphawallet.shadows.ShadowApp;
import com.alphawallet.shadows.ShadowKeyProviderFactory;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

@RunWith(AndroidJUnit4.class)
@Config(shadows = {ShadowApp.class, ShadowKeyProviderFactory.class})
public class RupayaNetworkTest
{
    @Test
    public void should_haveCorrect_NetworkConfiguration()
    {
        NetworkInfo info = EthereumNetworkBase.getNetworkByChain(EthereumNetworkBase.RUPAYA_ID);
        
        // Test network info
        assertThat(info.name, equalTo("Rupaya"));
        assertThat(info.symbol, equalTo("RUPX"));
        assertThat(info.rpcServerUrl, equalTo(EthereumNetworkBase.RUPAYA_RPC_URL));
        assertThat(info.etherscanUrl, equalTo("https://scan.rupaya.io/tx/"));
        assertThat(info.chainId, equalTo(499L));
        assertThat(info.isCustom, equalTo(false));
    }

    @Test
    public void should_haveCorrect_ShortName()
    {
        String shortName = EthereumNetworkBase.getShortChainName(EthereumNetworkBase.RUPAYA_ID);
        assertThat(shortName, equalTo("Rupaya"));
    }

    @Test
    public void should_haveCorrect_ChainSymbol()
    {
        String symbol = EthereumNetworkBase.getChainSymbol(EthereumNetworkBase.RUPAYA_ID);
        assertThat(symbol, equalTo("RUPX"));
    }
} 