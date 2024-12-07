package com.alphawallet.app;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsEqual.equalTo;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.alphawallet.app.repository.EthereumNetworkBase;
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
    public void should_getNodeURL_forRupaya()
    {
        assertThat(EthereumNetworkBase.getNodeURLByNetworkId(EthereumNetworkBase.RUPAYA_ID), 
                  equalTo(EthereumNetworkBase.RUPAYA_RPC_URL));
    }

    @Test 
    public void should_haveCorrect_NetworkInfo()
    {
        com.alphawallet.ethereum.NetworkInfo info = EthereumNetworkBase.getNetworkByChain(EthereumNetworkBase.RUPAYA_ID);
        assertThat(info.name, equalTo("Rupaya"));
        assertThat(info.symbol, equalTo("RUPX"));
        assertThat(info.rpcServerUrl, equalTo(EthereumNetworkBase.RUPAYA_RPC_URL));
    }
} 