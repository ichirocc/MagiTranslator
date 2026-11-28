/*
 * Copyright 2016 Luca Martino.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copyFile of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nie.translator.rtranslator.tools.gui;

import android.content.Context;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;

import java.util.List;

//FragmentPagerAdapter is deprecated in favour of ViewPager2 + FragmentStateAdapter. That move changes the tab lifecycle
//(the second tab is created lazily and only the shown one is resumed; the conversation screen calls the peers tab
//before it is shown), so it waits for a device check. The constructor below keeps today's behaviour explicitly.
@SuppressWarnings("deprecation")
public class CustomFragmentPagerAdapter extends FragmentPagerAdapter {
    private Context context;
    private List<String> titles;
    private Fragment[] fragments;

    public CustomFragmentPagerAdapter(Context context, FragmentManager fragmentManager, List<String> titles, Fragment[] fragments) {
        super(fragmentManager, BEHAVIOR_SET_USER_VISIBLE_HINT);   //what the one-argument constructor did
        this.context = context;
        this.titles = titles;
        this.fragments = fragments;
    }

    @Override
    public Fragment getItem(int position) {
        //return MyFragment.newInstance();
        return fragments[position];
    }

    @NonNull
    @Override
    public Object instantiateItem(@NonNull ViewGroup container, int position) {
        return super.instantiateItem(container, position);
    }

    @Override
    public CharSequence getPageTitle(int position) {
        //return CONTENT[position % CONTENT.length].toUpperCase();
        return titles.get(position);
    }

    @Override
    public int getCount() {
        // return CONTENT.length;
        return fragments.length;
    }

    public Fragment getFragment(int position) {
        return fragments[position];
    }

    @Override
    public int getItemPosition(@NonNull Object object) {
        return POSITION_NONE;
    }
}
